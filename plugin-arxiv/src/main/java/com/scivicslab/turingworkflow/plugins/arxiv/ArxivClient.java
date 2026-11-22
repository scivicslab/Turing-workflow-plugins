package com.scivicslab.turingworkflow.plugins.arxiv;

import com.scivicslab.pojoactor.action.ActionResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queries the arXiv API for bibliographic data and paper metadata.
 *
 * <p>The client maintains a shared rate limit across the entire JVM, ensuring no more than one request is sent every
 * three seconds. It also manages a cooldown period: if the API refuses requests (HTTP 429 or 503) after several
 * retries, the client will stop all outgoing requests for 15 minutes to avoid further penalties. During this cooldown,
 * any attempt to call an action returns an immediate failure stating how many seconds remain before the next attempt
 * is allowed.</p>
 *
 * <p>One instance may serve several workflows or threads at once, as the rate limiting and cooldown state are shared
 * globally across the JVM.</p>
 *
 * <p>The client uses HTTP/1.1 to ensure compatibility with the arXiv CDN's rate-limiting behavior.</p>
 */
public class ArxivClient {

    private static final Logger logger = Logger.getLogger(ArxivClient.class.getName());

    /**
     * The HTTPS endpoint {@code https://export.arxiv.org/api/query} used for all API requests to avoid the 301
     * redirect and the extra request cost associated with the {@code http://} address in the API guide.
     */
    private static final String BASE_URL = "https://export.arxiv.org/api/query";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RESULTS = 10;

    /** The minimum number of milliseconds to wait between requests to arXiv to comply with their API guidance. */
    private static final long MIN_INTERVAL_MILLIS = 3000;

    /**
     * The initial delay in milliseconds to wait after arXiv returns an HTTP 429 or 503 before attempting a retry.
     */
    private static final long RETRY_AFTER_MILLIS = 5000;

    /** The number of times a refused request is asked again before the workflow is told. */
    private static final int RETRIES = 3;

    /**
     * System property: the longest single wait, in seconds, before a refused request (HTTP 429 or 503) is asked again,
     * whatever the growing backoff or the server's {@code Retry-After} asks for. Default {@value
     * #DEFAULT_MAX_RETRY_WAIT_SECONDS}; with its 3 retries a refused request waits at most three times
     * this long in total.
     */
    public static final String MAX_RETRY_WAIT_PROPERTY = "turingworkflow.arxiv.maxRetryWaitSeconds";

    /** Default of {@value #MAX_RETRY_WAIT_PROPERTY}. */
    public static final long DEFAULT_MAX_RETRY_WAIT_SECONDS = 60;

    /**
     * The duration in milliseconds that this process refuses to send any requests after the retry attempts are
     * exhausted.
     */
    private static final long COOLDOWN_MILLIS = 15 * 60 * 1000L;

    /**
     * The maximum number of characters used for an abstract when it is too long to be shown in full in a list of
     * results.
     */
    private static final int ABSTRACT_SNIPPET = 400;

    private String email = "devteam@scivicslab.com";

    /**
     * A lock used to space out requests to the arXiv API by at least three seconds across the entire JVM.
     *
     * <p>This lock ensures that all instances of {@link ArxivClient} in a single process share the same three-second
     * interval between requests, preventing multiple actors from simultaneously hitting the rate limit.</p>
     */
    private static final Object PACE = new Object();
    private static long lastRequestAt = 0;

    /** The timestamp, in milliseconds, until which this process refuses to make any requests to arXiv. */
    private static volatile long refusingUntil = 0;

    /**
     * Communicates with the arXiv API using HTTP/1.1.
     *
     * <p>The client uses HTTP/1.1 instead of the JDK's default HTTP/2 because the arXiv CDN frequently returns "Rate
     * exceeded" errors to HTTP/2 requests, whereas the same queries succeed over HTTP/1.1.</p>
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Sets the contact email address used in the User-Agent header for requests to the arXiv API.
     *
     * @param address the contact email address to be used for requests
     * @return an action result that is a success with a message confirming the new email, or a failure if the address
     * is null or blank
     */
    public ActionResult setEmail(String address) {
        if (address == null || address.isBlank()) return new ActionResult(false, "Email is required");
        this.email = address.trim();
        return new ActionResult(true, "Email set to " + this.email);
    }

    /**
     * Searches arXiv for up to ten papers, returning them in order of relevance.
     *
     * @param query what to look for; plain words search every field or use arXiv prefixes like {@code cat:cs.DC} or
     * {@code abs:actor}
     *
     * @return an action result that is a success with a formatted list of papers if results are found, or a success
     * with a "No papers found" message if the query matches nothing; returns a failure if the query is null or blank,
     * or if the search fails due to network or rate limiting issues
     */
    public ActionResult searchPapers(String query) {
        return searchPapersTopK(query, DEFAULT_MAX_RESULTS, null);
    }

    /**
     * Searches arXiv with an explicit count and ordering, returning a text report of the papers found.
     *
     * @param query what to look for; plain words search every field, but arXiv's own field prefixes ({@code ti:},
     * {@code au:}, {@code abs:}, {@code cat:}) are passed through, so {@code cat:cs.DC AND ti:actor} works
     *
     * @param maxResults how many to return; ten when absent or if zero or negative
     * @param sort the ordering to use: {@code relevance} (default), {@code newest} (submission date), or {@code
     * updated} (last update)
     *
     * @return an action result that is a success with a text report of the papers if any are found, or a success with
     * a "No papers found" message if the query yields no results; it is not a success if the query is blank or if the
     * search fails due to network or API errors
     */
    public ActionResult searchPapersTopK(String query, Integer maxResults, String sort) {
        if (query == null || query.isBlank()) return new ActionResult(false, "Query is required");
        int count = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        String sortBy = resolveSort(sort);
        String url = BASE_URL + "?search_query=" + URLEncoder.encode(searchQuery(query), StandardCharsets.UTF_8)
                + "&start=0&max_results=" + count
                + "&sortBy=" + sortBy + "&sortOrder=descending";
        try {
            List<Entry> entries = entriesOf(get(url));
            if (entries.isEmpty()) return new ActionResult(true, "No papers found for: " + query);
            StringBuilder sb = new StringBuilder();
            sb.append("arXiv papers for \"").append(query).append("\" (showing ")
              .append(entries.size()).append(" by ").append(sortLabel(sortBy)).append("):\n\n");
            for (int i = 0; i < entries.size(); i++) {
                sb.append(i + 1).append(". ").append(entries.get(i).format(false)).append("\n\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "arXiv search failed for: " + query, e);
            return new ActionResult(false, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Returns a text description of a single paper, including its full abstract, for the given identifier.
     *
     * @param id the arXiv id — {@code 2203.02155}, {@code 2203.02155v1}, {@code arXiv:2203.02155} or an {@code
     * abs}/{@code pdf} URL
     *
     * @return an action result that is a success with the paper's details as the message, or a failure if the paper is
     * not found or the request fails
     */
    public ActionResult getPaper(String id) {
        Entry entry;
        try {
            entry = one(id);
        } catch (Exception e) {
            logger.log(Level.WARNING, "arXiv getPaper failed for: " + id, e);
            return new ActionResult(false, "Failed to get paper: " + e.getMessage());
        }
        return entry == null ? new ActionResult(false, "No such paper: " + id)
                             : new ActionResult(true, entry.format(true));
    }

    /**
     * Returns the URL where arXiv serves the PDF of the specified paper.
     *
     * @param id the arXiv id — {@code 2203.02155}, {@code 2203.02155v1}, {@code arXiv:2203.02155} or an {@code
     * abs}/{@code pdf} URL
     *
     * @return an action result that is a success and holds the PDF URL, or a failure if the paper is not found or has
     * no PDF available
     */
    public ActionResult getPdfUrl(String id) {
        Entry entry;
        try {
            entry = one(id);
        } catch (Exception e) {
            logger.log(Level.WARNING, "arXiv getPdfUrl failed for: " + id, e);
            return new ActionResult(false, "Failed to get PDF URL: " + e.getMessage());
        }
        if (entry == null) return new ActionResult(false, "No such paper: " + id);
        return entry.pdfUrl == null || entry.pdfUrl.isBlank()
                ? new ActionResult(false, "No PDF for: " + id)
                : new ActionResult(true, entry.pdfUrl);
    }

    /**
     * Returns a text report of up to ten papers by the specified author, ordered by submission date.
     *
     * @param query the author's name
     * @return an action result that is a success with a formatted list of papers if papers were found, or a success
     * with a "No papers found" message if the query yields no results; it is not a success if the query is blank or if
     * the search fails due to network or API errors
     */
    public ActionResult searchByAuthor(String query) {
        if (query == null || query.isBlank()) return new ActionResult(false, "Author is required");
        return searchPapersTopK("au:\"" + query.strip() + "\"", DEFAULT_MAX_RESULTS, "newest");
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Returns the single paper matching the given id, or {@code null} if no such paper exists.
     *
     * @param id the arXiv id, in any of the forms {@link #getPaper} accepts
     * @return the paper entry, or {@code null} if the ID is valid but no paper is found
     * @throws Exception if the request fails or the ID is blank
     */
    private Entry one(String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Paper id is required");
        String url = BASE_URL + "?id_list=" + URLEncoder.encode(bareId(id), StandardCharsets.UTF_8)
                + "&max_results=1";
        List<Entry> entries = entriesOf(get(url));
        return entries.isEmpty() ? null : entries.get(0);
    }

    /**
     * Returns a string that is the query to be sent to the arXiv API, ensuring it contains at least one field prefix.
     *
     * <p>If the query already contains a prefix such as {@code ti:}, {@code au:}, {@code abs:}, or {@code cat:}, it is
     * returned as is. Otherwise, the prefix {@code all:} is prepended to the query to search all fields.</p>
     */
    private static String searchQuery(String query) {
        String q = query.strip();
        return q.matches("(?s).*\\b(ti|au|abs|cat|co|jr|rn|id|all):.*") ? q : "all:" + q;
    }

    /**
     * Returns a normalized arXiv identifier from any of the common forms used in workflows.
     *
     * <p>The result is the bare identifier, such as {@code 2203.02155v1}, stripped of prefixes like {@code arXiv:},
     * {@code abs/}, {@code pdf/}, or {@code DOI:}, and without the {@code .pdf} extension.</p>
     *
     * @param id the identifier to normalize, such as a URL, a prefixed string, or a bare ID
     */
    static String bareId(String id) {
        String s = id.strip();
        int abs = s.indexOf("/abs/");
        if (abs >= 0) s = s.substring(abs + "/abs/".length());
        int pdf = s.indexOf("/pdf/");
        if (pdf >= 0) s = s.substring(pdf + "/pdf/".length());
        if (s.toLowerCase(Locale.ROOT).startsWith("arxiv:")) s = s.substring("arxiv:".length());
        if (s.endsWith(".pdf")) s = s.substring(0, s.length() - ".pdf".length());
        return s.strip();
    }

    private static String resolveSort(String friendly) {
        if (friendly == null || friendly.isBlank()) return "relevance";
        return switch (friendly.strip().toLowerCase(Locale.ROOT)) {
            case "newest", "date", "latest", "recent", "submitted", "submitteddate" -> "submittedDate";
            case "updated", "lastupdated", "lastupdateddate" -> "lastUpdatedDate";
            default -> "relevance";
        };
    }

    private static String sortLabel(String sortBy) {
        return switch (sortBy) {
            case "submittedDate"   -> "submission date (newest first)";
            case "lastUpdatedDate" -> "last update (newest first)";
            default -> "relevance";
        };
    }

    /**
     * Holds the bibliographic data of a single arXiv paper.
     *
     * <p>The data includes the title, authors, publication and update dates, categories, DOI, journal reference, and
     * links to the abstract and PDF. The summary is truncated to 400 characters unless a verbose format is
     * requested.</p>
     *
     * @param id the arXiv identifier
     * @param title the paper title
     * @param summary the paper abstract
     * @param authors the list of author names
     * @param published the publication date
     * @param updated the last update date
     * @param categories the list of subject categories
     * @param doi the Digital Object Identifier
     * @param comment any additional comments from the authors
     * @param journalRef the journal reference string
     * @param absUrl the URL to the paper's abstract page
     * @param pdfUrl the URL to the paper's PDF file
     */
    private record Entry(String id, String title, String summary, List<String> authors,
                         String published, String updated, List<String> categories,
                         String doi, String comment, String journalRef, String absUrl, String pdfUrl) {

        String format(boolean verbose) {
            StringBuilder sb = new StringBuilder();
            sb.append('"').append(title).append('"');
            if (published != null && published.length() >= 4) sb.append(" (").append(published, 0, 4).append(")");
            sb.append('\n');
            if (!authors.isEmpty()) {
                sb.append("Authors: ");
                int shown = verbose ? authors.size() : Math.min(5, authors.size());
                sb.append(String.join(", ", authors.subList(0, shown)));
                if (shown < authors.size()) sb.append(", … (").append(authors.size()).append(" total)");
                sb.append('\n');
            }
            if (!categories.isEmpty()) sb.append("Categories: ").append(String.join(", ", categories)).append('\n');
            if (journalRef != null && !journalRef.isBlank()) sb.append("Journal: ").append(journalRef).append('\n');
            if (doi != null && !doi.isBlank()) sb.append("DOI: https://doi.org/").append(doi).append('\n');
            if (summary != null && !summary.isBlank()) {
                String text = summary.replaceAll("\\s+", " ").strip();
                sb.append("Abstract: ")
                  .append(verbose || text.length() <= ABSTRACT_SNIPPET
                          ? text : text.substring(0, ABSTRACT_SNIPPET) + "…")
                  .append('\n');
            }
            if (verbose && comment != null && !comment.isBlank()) {
                sb.append("Comment: ").append(comment).append('\n');
            }
            if (updated != null && !updated.isBlank() && verbose) sb.append("Updated: ").append(updated).append('\n');
            if (pdfUrl != null && !pdfUrl.isBlank()) sb.append("PDF: ").append(pdfUrl).append('\n');
            sb.append("arXiv: ").append(absUrl == null || absUrl.isBlank() ? id : absUrl);
            return sb.toString().stripTrailing();
        }
    }

    private static List<Entry> entriesOf(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // The reply comes from the network: no external entities, no doctypes.
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        List<Entry> out = new ArrayList<>();
        NodeList entries = doc.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element e = (Element) entries.item(i);
            String id = text(e, "http://www.w3.org/2005/Atom", "id");
            List<String> authors = new ArrayList<>();
            NodeList authorNodes = e.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "author");
            for (int a = 0; a < authorNodes.getLength(); a++) {
                String name = text((Element) authorNodes.item(a), "http://www.w3.org/2005/Atom", "name");
                if (name != null && !name.isBlank()) authors.add(name.strip());
            }
            List<String> categories = new ArrayList<>();
            NodeList categoryNodes = e.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "category");
            for (int c = 0; c < categoryNodes.getLength(); c++) {
                String term = ((Element) categoryNodes.item(c)).getAttribute("term");
                if (term != null && !term.isBlank()) categories.add(term);
            }
            String absUrl = null;
            String pdfUrl = null;
            NodeList links = e.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link");
            for (int l = 0; l < links.getLength(); l++) {
                Element link = (Element) links.item(l);
                String href = link.getAttribute("href");
                if ("pdf".equals(link.getAttribute("title"))) pdfUrl = href;
                else if ("alternate".equals(link.getAttribute("rel"))) absUrl = href;
            }
            out.add(new Entry(id,
                    strip(text(e, "http://www.w3.org/2005/Atom", "title")),
                    text(e, "http://www.w3.org/2005/Atom", "summary"),
                    authors,
                    text(e, "http://www.w3.org/2005/Atom", "published"),
                    text(e, "http://www.w3.org/2005/Atom", "updated"),
                    categories,
                    text(e, "http://arxiv.org/schemas/atom", "doi"),
                    text(e, "http://arxiv.org/schemas/atom", "comment"),
                    text(e, "http://arxiv.org/schemas/atom", "journal_ref"),
                    absUrl, pdfUrl));
        }
        return out;
    }

    private static String text(Element parent, String ns, String name) {
        NodeList nodes = parent.getElementsByTagNameNS(ns, name);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getParentNode() == parent) return node.getTextContent();
        }
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private static String strip(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").strip();
    }

    /**
     * One GET, no sooner than three seconds after the last one.
     *
     * <p>The wait is here rather than in the workflow because the limit belongs to the API, not to
     * any one caller: a plan that reads ten search results one by one would otherwise have to
     * carry the pause in its own steps.</p>
     */
    /**
     * Performs an HTTP GET request to the specified URL, waiting out temporary service refusals.
     *
     * <p>If the server responds with an HTTP 429 or 503, the method retries up to three times with increasing delays,
     * or longer if the server's {@code Retry-After} header specifies a greater duration. If the server is currently
     * refusing the caller due to a previous cooldown, the method throws an exception immediately stating how many
     * seconds remain before the next attempt is allowed. If the retries are exhausted or the server is unreachable,
     * the method throws an exception.</p>
     *
     * @param url the URL to query via GET
     * @return the response body as a string
     * @throws Exception if the server is currently in a cooldown period, if the retries are exhausted, or if the
     * server returns a non-200 status code
     */
    String get(String url) throws Exception {
        long waitingOut = refusingUntil - System.currentTimeMillis();
        if (waitingOut > 0) {
            throw new Exception("arXiv refused this caller; not asking again for another "
                    + (waitingOut / 1000) + "s");
        }
        boolean refused = false;
        HttpResponse<String> resp = null;
        long wait = RETRY_AFTER_MILLIS;
        long maxWait = SystemPropertyLimit.read(MAX_RETRY_WAIT_PROPERTY, DEFAULT_MAX_RETRY_WAIT_SECONDS) * 1000L;
        for (int attempt = 0; attempt <= RETRIES; attempt++) {
            try {
                resp = send(url);
            } catch (Exception e) {
                // A refusal followed by a request that never answers is the same trouble wearing
                // another face; stop asking either way.
                if (refused) startCooldown();
                throw e;
            }
            if (!busy(resp.statusCode())) break;
            refused = true;
            if (attempt == RETRIES) break;
            long asked = retryAfterMillis(resp);
            if (asked > wait) wait = asked;
            // a Retry-After of hours, tripled on each retry, would hold the action for most of a day
            wait = Math.min(wait, maxWait);
            logger.warning("arXiv answered " + resp.statusCode() + "; waiting " + wait
                    + "ms and asking again (attempt " + (attempt + 1) + " of " + RETRIES + ")");
            Thread.sleep(wait);
            wait *= 3;
        }
        if (busy(resp.statusCode())) startCooldown();
        if (resp.statusCode() != 200) {
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /**
     * Sets a timestamp that prevents any further requests to arXiv for 15 minutes.
     *
     * <p>This is triggered when the API returns a rate limit or service unavailable error after all retries have been
     * exhausted. During this period, any attempt to perform an action will fail immediately without making a network
     * request, returning a message that specifies how many seconds remain in the cooldown.</p>
     */
    private static void startCooldown() {
        refusingUntil = System.currentTimeMillis() + COOLDOWN_MILLIS;
        logger.warning("arXiv is refusing this caller; no request will be sent for the next "
                + (COOLDOWN_MILLIS / 60000) + " minutes");
    }

    /** One GET, no sooner than {@link #MIN_INTERVAL_MILLIS} after the last one from this JVM. */
    /**
     * Returns the number of milliseconds to wait, as specified by the {@code Retry-After} header, or 0 if the header
     * is absent or contains an HTTP-date.
     *
     * @param resp the HTTP response to inspect
     * @return the number of milliseconds to wait, or 0 if the header is absent or contains an HTTP-date
     */
    private static long retryAfterMillis(HttpResponse<String> resp) {
        return resp.headers().firstValue("retry-after").map(value -> {
            try {
                return Long.parseLong(value.strip()) * 1000L;
            } catch (NumberFormatException e) {
                return 0L;                      // an HTTP-date; the fixed waits below cover it
            }
        }).orElse(0L);
    }

    /**
     * Returns true if the status code indicates the service is temporarily unavailable or rate-limiting the caller.
     *
     * @param status the HTTP response status code
     */
    private static boolean busy(int status) {
        return status == 429 || status == 503;
    }

    private HttpResponse<String> send(String url) throws Exception {
        synchronized (PACE) {
            long since = System.currentTimeMillis() - lastRequestAt;
            if (lastRequestAt > 0 && since < MIN_INTERVAL_MILLIS) {
                Thread.sleep(MIN_INTERVAL_MILLIS - since);
            }
            lastRequestAt = System.currentTimeMillis();
        }
        logger.info("arXiv GET " + url);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "TuringWorkflow/4.1.0 (mailto:" + email + ")")
                .header("Accept", "application/atom+xml")
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
