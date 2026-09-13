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
 * arXiv の API への問い合わせを保持する素のオブジェクト。アクターとして動かすのは
 * {@link ArxivActor} の役目であり、「アクターであること」はこのクラスの性質ではない
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01}）。
 *
 * <p>The arXiv API answers Atom XML, not JSON, so the responses are read with the JDK's own XML
 * parser rather than the {@code org.json} this plugin's siblings use. External entities are turned
 * off: the reply is a document from the network.</p>
 *
 * <p>arXiv asks callers for one request every three seconds and for a mail address in the user
 * agent. Both are kept here rather than left to the caller: a workflow that loops over search
 * results must not have to remember them.</p>
 *
 * <p>Base URL: {@code https://export.arxiv.org/api/query}</p>
 */
public class ArxivClient {

    private static final Logger logger = Logger.getLogger(ArxivClient.class.getName());

    /**
     * HTTPS rather than the {@code http://export.arxiv.org} the API guide prints: that address
     * answers 301 and the redirect costs a second request against a rate limit of one every three
     * seconds.
     */
    private static final String BASE_URL = "https://export.arxiv.org/api/query";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RESULTS = 10;

    /** What arXiv's API guidance asks for: no more than one request every three seconds. */
    private static final long MIN_INTERVAL_MILLIS = 3000;

    /** How long to wait after arXiv says the pace was too fast, before asking once more. */
    private static final long RETRY_AFTER_MILLIS = 5000;

    /** How many times a refused request is asked again before the workflow is told. */
    private static final int RETRIES = 3;

    /** How long an abstract is allowed to be in a list of results. */
    private static final int ABSTRACT_SNIPPET = 400;

    private String email = "devteam@scivicslab.com";

    /**
     * When the last request went out, for the whole JVM rather than for one instance: the three
     * seconds belong to arXiv's endpoint, not to a client object. Two actors created in one process
     * — a workflow's and a plan's — would otherwise take turns being refused with HTTP 429.
     */
    private static final Object PACE = new Object();
    private static long lastRequestAt = 0;

    /**
     * HTTP/1.1, not the JDK's default of trying HTTP/2 first. arXiv sits behind a CDN that answers
     * "Rate exceeded" to this client's HTTP/2 searches while the same query over HTTP/1.1 — and the
     * same client's id_list calls — go through.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Sets the address sent in the user agent, as arXiv's API guidance asks for.
     *
     * @param address the caller's mail address
     */
    public ActionResult setEmail(String address) {
        if (address == null || address.isBlank()) return new ActionResult(false, "Email is required");
        this.email = address.trim();
        return new ActionResult(true, "Email set to " + this.email);
    }

    /**
     * Searches arXiv, ten papers, most relevant first.
     *
     * @param query what to look for; plain words search every field
     */
    public ActionResult searchPapers(String query) {
        return searchPapersTopK(query, DEFAULT_MAX_RESULTS, null);
    }

    /**
     * Searches arXiv with an explicit count and ordering.
     *
     * @param query      what to look for. Plain words search every field; arXiv's own field
     *                   prefixes ({@code ti:}, {@code au:}, {@code abs:}, {@code cat:}) are passed
     *                   through, so {@code cat:cs.DC AND ti:actor} works
     * @param maxResults how many to return; ten when absent
     * @param sort       {@code relevance}, {@code newest} (submission date) or {@code updated};
     *                   relevance when absent
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
     * Reads one paper, with its whole abstract.
     *
     * @param id the arXiv id — {@code 2203.02155}, {@code 2203.02155v1},
     *           {@code arXiv:2203.02155} or an {@code abs}/{@code pdf} URL
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
     * The PDF of one paper.
     *
     * @param id the arXiv id, in the same forms {@link #getPaper} takes
     * @return the URL arXiv serves the PDF at
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
     * Searches by author name.
     *
     * @param query the author's name
     */
    public ActionResult searchByAuthor(String query) {
        if (query == null || query.isBlank()) return new ActionResult(false, "Author is required");
        return searchPapersTopK("au:\"" + query.strip() + "\"", DEFAULT_MAX_RESULTS, "newest");
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** @return the one paper of that id, or {@code null} when arXiv knows none */
    private Entry one(String id) throws Exception {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Paper id is required");
        String url = BASE_URL + "?id_list=" + URLEncoder.encode(bareId(id), StandardCharsets.UTF_8)
                + "&max_results=1";
        List<Entry> entries = entriesOf(get(url));
        return entries.isEmpty() ? null : entries.get(0);
    }

    /** @return what goes in {@code search_query}: the caller's own prefixes, or {@code all:} */
    private static String searchQuery(String query) {
        String q = query.strip();
        return q.matches("(?s).*\\b(ti|au|abs|cat|co|jr|rn|id|all):.*") ? q : "all:" + q;
    }

    /** @return {@code 2203.02155v1} out of any of the forms a person writes it in */
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

    /** One paper, as much of the Atom entry as this plugin reports. */
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
     * One GET, waiting out a refusal rather than failing at the first one.
     *
     * <p>arXiv answers HTTP 429 when the pool it shares among callers without a key is busy. That
     * is a matter of when, not of what was asked, so this waits and asks again — three times, for
     * 5, 15 and 45 seconds, and only then reports the refusal to the workflow.</p>
     */
    private String get(String url) throws Exception {
        HttpResponse<String> resp = send(url);
        long wait = RETRY_AFTER_MILLIS;
        for (int attempt = 1; busy(resp.statusCode()) && attempt <= RETRIES; attempt++) {
            logger.warning("arXiv answered " + resp.statusCode() + "; waiting " + wait
                    + "ms and asking again (attempt " + attempt + " of " + RETRIES + ")");
            Thread.sleep(wait);
            wait *= 3;
            resp = send(url);
        }
        if (resp.statusCode() != 200) {
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /** One GET, no sooner than {@link #MIN_INTERVAL_MILLIS} after the last one from this JVM. */
    /** @return whether the answer says "not now": too many requests, or the service is out */
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
