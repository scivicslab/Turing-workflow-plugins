package com.scivicslab.turingworkflow.plugins.semanticscholar;

import com.scivicslab.pojoactor.action.ActionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queries the Semantic Scholar Graph API to retrieve bibliographic data and citation graphs.
 *
 * <ul><li><code>searchPapers(query)</code> returns a list of up to ten papers matching the query, ranked by
 * relevance.</li><li><code>searchPapersTopK(query, limit, year, fields)</code> returns a list of papers matching the
 * query, with a specific count, year range, and field of study.</li><li><code>getPaper(id)</code> returns a single
 * paper's details including its abstract.</li><li><code>getPdfUrl(id)</code> returns the URL of an open-access PDF if
 * one is available.</li><li><code>getCitations(id, limit)</code> returns a list of papers that cite the given
 * paper.</li><li><code>getReferences(id, limit)</code> returns a list of papers that the given paper
 * cites.</li><li><code>setApiKey(key)</code> sets the API key used in the {@code x-api-key} header.</li></ul>
 *
 * <p>An API key is optional; without one, the client uses a shared pool that is subject to rate limits. If the API
 * returns an HTTP 429 or 503, the client retries up to three times with increasing delays (5, 15, and 45 seconds)
 * before failing. If retries are exhausted, the client enters a 15-minute cooldown period during which all subsequent
 * calls fail immediately without attempting network communication.</p>
 *
 * <p>One instance may serve several workflows or threads at once, but the request pacing is shared across the entire
 * JVM to respect the API's limits.</p>
 */
public class SemanticScholarClient {

    private static final Logger logger = Logger.getLogger(SemanticScholarClient.class.getName());

    private static final String BASE_URL = "https://api.semanticscholar.org/graph/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_LIMIT = 10;

    /**
     * The minimum time in milliseconds to wait between requests to the shared Semantic Scholar pool to avoid rate
     * limits.
     */
    private static final long MIN_INTERVAL_MILLIS = 3000;

    /**
     * The initial duration in milliseconds to wait after receiving an HTTP 429 or 503 response before attempting a
     * retry.
     */
    private static final long RETRY_AFTER_MILLIS = 5000;

    /** The number of times a refused request is asked again before the workflow is told. */
    private static final int RETRIES = 3;

    /**
     * The duration in milliseconds that the process refuses to send any new requests after all retries have been
     * exhausted.
     */
    private static final long COOLDOWN_MILLIS = 15 * 60 * 1000L;

    /**
     * The list of fields requested from the Semantic Scholar API when retrieving multiple papers, including title,
     * year, authors, venue, citation counts, external IDs, and open access PDF availability.
     */
    private static final String LIST_FIELDS =
            "title,year,authors,venue,citationCount,influentialCitationCount,externalIds,openAccessPdf";

    /** The fields to request for a single paper, including the abstract, publication types, and TLDR. */
    private static final String ONE_FIELDS = LIST_FIELDS + ",abstract,publicationTypes,tldr";

    /** The maximum number of characters an abstract may occupy in a list of search results. */
    private static final int ABSTRACT_SNIPPET = 400;

    /**
     * A lock used to pace requests to the Semantic Scholar API across the entire JVM.
     *
     * <p>It ensures that no two requests, regardless of which instance of {@link SemanticScholarClient} makes them,
     * are sent closer than 3 seconds apart.</p>
     */
    private static final Object PACE = new Object();
    private static long lastRequestAt = 0;

    /**
     * The timestamp, in milliseconds since the epoch, until which this process refuses to make any network requests.
     */
    private static volatile long refusingUntil = 0;

    private String apiKey = "";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Sets the API key sent as {@code x-api-key}. Without one the shared pool is used.
     *
     * @param key the key Semantic Scholar issued
     * @return an action result that is a success if the key is not blank, with a message containing the key's length;
     * otherwise, a failure indicating that a key is required
     */
    public ActionResult setApiKey(String key) {
        if (key == null || key.isBlank()) return new ActionResult(false, "API key is required");
        this.apiKey = key.trim();
        return new ActionResult(true, "API key set (" + this.apiKey.length() + " chars)");
    }

    /**
     * Returns an action result containing a list of up to ten papers ranked by the API as a formatted text block.
     *
     * @param query the search term to look for
     */
    public ActionResult searchPapers(String query) {
        return searchPapersTopK(query, DEFAULT_LIMIT, null, null);
    }

    /**
     * Searches for papers using a query and optional filters for count, year, and field of study. Returns an action
     * result containing a formatted list of up to {@code limit} papers, including their titles, years, venues,
     * citation counts, and external IDs.
     *
     * If {@code limit} is null or less than or equal to 0, it returns 10 papers. If {@code year} is absent, it returns
     * papers from all years. If {@code fields} is absent, it returns papers from all fields of study.
     *
     * Returns a success result with a text message listing the papers if results are found, or a success result with a
     * message stating no papers were found if the query matches nothing. Returns a failure result if the query is
     * blank or if the search fails.
     *
     * @param query the search term or expression (e.g., {@code "cat:cs.AI AND abs:actor"})
     * @param limit the maximum number of papers to return; 10 if null or non-positive
     * @param year a year or a range such as {@code "2020-2024"}; all years if null or blank
     * @param fields a field of study such as {@code "Computer Science"}; all fields if null or blank
     * @return an action result that is a success with a formatted list of papers if found, a success with a "No papers
     * found" message if the query is valid but empty, or a failure if the query is blank or the request fails
     */
    public ActionResult searchPapersTopK(String query, Integer limit, String year, String fields) {
        if (query == null || query.isBlank()) return new ActionResult(false, "Query is required");
        int count = limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/paper/search?query=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8))
                .append("&limit=").append(count)
                .append("&fields=").append(LIST_FIELDS);
        if (year != null && !year.isBlank()) {
            url.append("&year=").append(URLEncoder.encode(year.strip(), StandardCharsets.UTF_8));
        }
        if (fields != null && !fields.isBlank()) {
            url.append("&fieldsOfStudy=").append(URLEncoder.encode(fields.strip(), StandardCharsets.UTF_8));
        }
        try {
            JSONObject root = new JSONObject(get(url.toString()));
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.isEmpty()) return new ActionResult(true, "No papers found for: " + query);
            int total = root.optInt("total", data.length());
            StringBuilder sb = new StringBuilder();
            sb.append("Semantic Scholar papers for \"").append(query).append("\" (")
              .append(total).append(" total, showing ").append(data.length()).append("):\n\n");
            for (int i = 0; i < data.length(); i++) {
                sb.append(i + 1).append(". ").append(formatPaper(data.getJSONObject(i), false)).append("\n\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar search failed for: " + query, e);
            return new ActionResult(false, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Returns a text description of a single paper, including its abstract, title, and metadata.
     *
     * @param id a Semantic Scholar id, or one of the forms the API takes as a prefix: {@code DOI:10.1038/…}, {@code
     * arXiv:2203.02155}, {@code CorpusId:…}, {@code PMID:…}. A bare DOI or arXiv id is prefixed here.
     *
     * @return an action result that is a success and holds the formatted paper text in its message, or is not a
     * success if the id is blank or the paper cannot be found.
     */
    public ActionResult getPaper(String id) {
        if (id == null || id.isBlank()) return new ActionResult(false, "Paper id is required");
        String url = BASE_URL + "/paper/" + URLEncoder.encode(resolveId(id), StandardCharsets.UTF_8)
                .replace("%3A", ":").replace("%2F", "/")
                + "?fields=" + ONE_FIELDS;
        try {
            return new ActionResult(true, formatPaper(new JSONObject(get(url)), true));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar getPaper failed for: " + id, e);
            return new ActionResult(false, "Failed to get paper: " + e.getMessage());
        }
    }

    /**
     * Returns the URL of the open-access PDF for a paper, if Semantic Scholar has one.
     *
     * @param id the paper, in the same forms {@link #getPaper} takes
     * @return an action result that is a success and holds the URL string if a PDF is found, or is not a success with
     * a message stating no open-access PDF was found or describing a failure
     */
    public ActionResult getPdfUrl(String id) {
        if (id == null || id.isBlank()) return new ActionResult(false, "Paper id is required");
        String url = BASE_URL + "/paper/" + URLEncoder.encode(resolveId(id), StandardCharsets.UTF_8)
                .replace("%3A", ":").replace("%2F", "/")
                + "?fields=title,openAccessPdf,externalIds";
        try {
            JSONObject paper = new JSONObject(get(url));
            JSONObject pdf = paper.optJSONObject("openAccessPdf");
            String link = pdf == null ? null : pdf.optString("url", null);
            if (link != null && !link.isBlank()) return new ActionResult(true, link);
            return new ActionResult(false, "No open-access PDF for: " + paper.optString("title", id));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar getPdfUrl failed for: " + id, e);
            return new ActionResult(false, "Failed to get PDF URL: " + e.getMessage());
        }
    }

    /**
     * Returns a text list of the papers that cite the specified paper, ordered by the API's default (newest first).
     *
     * @param id the paper, in the same forms {@link #getPaper} takes
     * @param limit how many to return; ten when absent
     * @return an action result that is a success with a formatted list of citing papers, or a failure if the paper id
     * is required or the request fails
     */
    public ActionResult getCitations(String id, Integer limit) {
        return related(id, limit, "citations", "citingPaper", "Papers citing");
    }

    /**
     * Returns an action result containing a list of papers that the specified paper cites.
     *
     * @param id the paper, in the same forms {@link #getPaper} takes
     * @param limit how many to return; ten when absent
     * @return a success result with a formatted list of papers cited by the given paper, or a failure result if the ID
     * is blank or the request fails
     */
    public ActionResult getReferences(String id, Integer limit) {
        return related(id, limit, "references", "citedPaper", "Papers cited by");
    }

    // ── internals ────────────────────────────────────────────────────────────

    private ActionResult related(String id, Integer limit, String path, String key, String heading) {
        if (id == null || id.isBlank()) return new ActionResult(false, "Paper id is required");
        int count = limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
        String url = BASE_URL + "/paper/" + URLEncoder.encode(resolveId(id), StandardCharsets.UTF_8)
                .replace("%3A", ":").replace("%2F", "/")
                + "/" + path + "?limit=" + count + "&fields=" + LIST_FIELDS;
        try {
            JSONObject root = new JSONObject(get(url));
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.isEmpty()) return new ActionResult(true, "None found for: " + id);
            StringBuilder sb = new StringBuilder(heading).append(" ").append(id)
                    .append(" (showing ").append(data.length()).append("):\n\n");
            for (int i = 0; i < data.length(); i++) {
                JSONObject paper = data.getJSONObject(i).optJSONObject(key);
                if (paper == null) continue;
                sb.append(i + 1).append(". ").append(formatPaper(paper, false)).append("\n\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar " + path + " failed for: " + id, e);
            return new ActionResult(false, "Failed to get " + path + ": " + e.getMessage());
        }
    }

    /**
     * Returns a string that is a valid Semantic Scholar identifier.
     *
     * If the input is a bare DOI or an arXiv ID, the result is the same identifier prefixed with {@code DOI:} or
     * {@code arXiv:} respectively. If the input already contains a colon, it is returned as is.
     *
     * @param id the paper identifier to resolve, such as a DOI, an arXiv ID, or a Semantic Scholar ID
     */
    static String resolveId(String id) {
        String s = id.strip();
        if (s.contains(":")) return s;                      // already prefixed (DOI:, arXiv:, PMID:, …)
        if (s.startsWith("10.")) return "DOI:" + s;
        if (s.startsWith("https://doi.org/")) return "DOI:" + s.substring("https://doi.org/".length());
        if (s.matches("\\d{4}\\.\\d{4,5}(v\\d+)?")) return "arXiv:" + s;
        return s;                                            // a Semantic Scholar id
    }

    private static String formatPaper(JSONObject p, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(p.optString("title", "(no title)")).append('"');
        int year = p.optInt("year", 0);
        if (year > 0) sb.append(" (").append(year).append(")");
        String venue = p.optString("venue", "");
        if (!venue.isBlank()) sb.append(", ").append(venue);
        sb.append(", cited ").append(p.optInt("citationCount", 0)).append(" times");
        int influential = p.optInt("influentialCitationCount", 0);
        if (influential > 0) sb.append(" (").append(influential).append(" influential)");
        sb.append('\n');

        JSONArray authors = p.optJSONArray("authors");
        if (authors != null && !authors.isEmpty()) {
            sb.append("Authors: ");
            int shown = verbose ? authors.length() : Math.min(5, authors.length());
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(", ");
                sb.append(authors.getJSONObject(i).optString("name", ""));
            }
            if (shown < authors.length()) sb.append(", … (").append(authors.length()).append(" total)");
            sb.append('\n');
        }

        JSONObject ids = p.optJSONObject("externalIds");
        if (ids != null) {
            String doi = ids.optString("DOI", null);
            if (doi != null && !doi.isBlank()) sb.append("DOI: https://doi.org/").append(doi).append('\n');
            String arxiv = ids.optString("ArXiv", null);
            if (arxiv != null && !arxiv.isBlank()) sb.append("arXiv: https://arxiv.org/abs/").append(arxiv).append('\n');
        }

        JSONObject tldr = p.optJSONObject("tldr");
        if (tldr != null) {
            String text = tldr.optString("text", "");
            if (!text.isBlank()) sb.append("TLDR: ").append(text).append('\n');
        }

        String summary = p.optString("abstract", "");
        if (!summary.isBlank()) {
            String text = summary.replaceAll("\\s+", " ").strip();
            sb.append("Abstract: ")
              .append(verbose || text.length() <= ABSTRACT_SNIPPET
                      ? text : text.substring(0, ABSTRACT_SNIPPET) + "…")
              .append('\n');
        }

        JSONObject pdf = p.optJSONObject("openAccessPdf");
        if (pdf != null) {
            String link = pdf.optString("url", "");
            if (!link.isBlank()) sb.append("PDF: ").append(link).append('\n');
        }

        String paperId = p.optString("paperId", "");
        if (!paperId.isBlank()) {
            sb.append("Semantic Scholar: https://www.semanticscholar.org/paper/").append(paperId);
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Performs an HTTP GET request to the specified URL, retrying if the service is busy.
     *
     * <p>If the service returns an HTTP 429 (Too Many Requests) or 503 (Service Unavailable), the method waits and
     * retries up to three times, with increasing delays of 5, 15, and 45 seconds, or longer if the server's {@code
     * Retry-After} header specifies a greater duration. If the retries are exhausted or the service is unavailable,
     * the method throws an exception. If the service is currently in a cooldown period due to previous failures, the
     * method throws an exception immediately without attempting a request, stating how many seconds remain until the
     * next allowed attempt.</p>
     *
     * <p>Returns the response body as a string if the request succeeds with an HTTP 200 status.</p>
     *
     * @param url the full URL to query
     * @throws Exception if the request fails, the service is in a cooldown period, or the server returns a non-200
     * status code.
     */
    private String get(String url) throws Exception {
        long waitingOut = refusingUntil - System.currentTimeMillis();
        if (waitingOut > 0) {
            throw new Exception("Semantic Scholar refused this caller; not asking again for another "
                    + (waitingOut / 1000) + "s");
        }
        boolean refused = false;
        HttpResponse<String> resp = null;
        long wait = RETRY_AFTER_MILLIS;
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
            logger.warning("Semantic Scholar answered " + resp.statusCode() + "; waiting " + wait
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
     * Prevents all outgoing requests to Semantic Scholar for a 15-minute period.
     *
     * <p>This occurs when the API has refused the caller or retries have been exhausted. During this window, any
     * attempt to perform an action fails immediately with a message stating how many seconds remain before the next
     * allowed request, and no network traffic is sent.</p>
     */
    private static void startCooldown() {
        refusingUntil = System.currentTimeMillis() + COOLDOWN_MILLIS;
        logger.warning("Semantic Scholar is refusing this caller; no request will be sent for the next "
                + (COOLDOWN_MILLIS / 60000) + " minutes");
    }

    /**
     * Returns the number of milliseconds the {@code Retry-After} header asks to wait, or 0 if the header is absent or
     * contains an HTTP date.
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
     * Returns true if the HTTP status code indicates the service is temporarily unavailable or rate-limited.
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
        logger.info("Semantic Scholar GET " + url);
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "TuringWorkflow/4.1.0")
                .header("Accept", "application/json")
                .GET();
        if (!apiKey.isBlank()) req.header("x-api-key", apiKey);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
