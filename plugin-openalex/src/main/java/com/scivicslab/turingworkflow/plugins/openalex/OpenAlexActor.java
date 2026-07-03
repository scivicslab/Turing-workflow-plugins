package com.scivicslab.turingworkflow.plugins.openalex;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Actor that queries the OpenAlex open scholarly metadata API.
 *
 * <p>OpenAlex is a free, open-access catalog of over 250 million scholarly works,
 * authors, institutions, and topics. No API key is required. The polite pool
 * (higher rate limits) is enabled by supplying a contact email via {@code setEmail}
 * or by configuring the default at construction time.</p>
 *
 * <p>Base URL: {@code https://api.openalex.org}</p>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code setEmail}        — set contact email for polite pool (higher rate limit)</li>
 *   <li>{@code searchWorks}     — search papers by keyword (default top 10, sorted by citation count)</li>
 *   <li>{@code searchWorksTopK} — search papers with explicit count via JSON: {"query":"...","perPage":5}</li>
 *   <li>{@code getWork}         — retrieve one paper by OpenAlex ID (W...) or DOI</li>
 *   <li>{@code searchAuthors}   — search authors by name</li>
 * </ul>
 */
public class OpenAlexActor extends IIActorRef<OpenAlexActor> {

    private static final Logger logger = Logger.getLogger(OpenAlexActor.class.getName());
    private static final String BASE_URL = "https://api.openalex.org";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_PER_PAGE = 10;
    /** Default result ordering (most-cited first). */
    private static final String DEFAULT_SORT = "cited_by_count:desc";

    private String email = "devteam@scivicslab.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public OpenAlexActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Sets the contact email used in the OpenAlex polite pool header and mailto param.
     * Providing an email enables higher rate limits (10 req/s → ~100 req/s).
     *
     * @param args contact email address
     */
    @Action("setEmail")
    public ActionResult setEmail(String args) {
        String e = parseFirstArgument(args);
        if (e == null || e.isBlank()) return new ActionResult(false, "Email is required");
        this.email = e.trim();
        return new ActionResult(true, "Email set to " + this.email);
    }

    /**
     * Searches OpenAlex works by keyword, returns top 10 sorted by citation count.
     *
     * <p>Result includes title, year, journal, citation count, DOI, abstract snippet,
     * and open-access URL for each paper.</p>
     *
     * @param args keyword query string
     */
    @Action("searchWorks")
    public ActionResult searchWorks(String args) {
        return doSearchWorks(parseFirstArgument(args), DEFAULT_PER_PAGE);
    }

    /**
     * Searches OpenAlex works with an explicit result count.
     *
     * <p>Expected argument: JSON object {@code {"query": "...", "perPage": 5, "sort": "citations"}}.
     * Falls back to perPage=10 and sort=citations if absent.</p>
     *
     * <p>{@code sort} accepts friendly names — {@code citations} (most cited first),
     * {@code relevance} (best title/abstract match first), {@code newest} (most recent first) —
     * or a raw OpenAlex sort string (e.g. {@code publication_date:desc}).</p>
     *
     * @param args JSON object with {@code query} and optional {@code perPage} and {@code sort}
     */
    @Action("searchWorksTopK")
    public ActionResult searchWorksTopK(String args) {
        // Turing Workflow wraps a single string argument in a JSON array (["{...}"]), so unwrap it
        // FIRST — otherwise the JSON-object branch never matches and the whole JSON is used as the query.
        String inner = parseFirstArgument(args);
        String trimmed = inner == null ? "" : inner.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(trimmed);
                String query = obj.optString("query", "");
                int perPage = obj.optInt("perPage", DEFAULT_PER_PAGE);
                String sort = obj.optString("sort", DEFAULT_SORT);
                return doSearchWorks(query, perPage, sort);
            } catch (Exception e) {
                return new ActionResult(false, "Invalid JSON: " + e.getMessage());
            }
        }
        return doSearchWorks(inner, DEFAULT_PER_PAGE);
    }

    /**
     * Retrieves a single paper by OpenAlex ID or DOI.
     *
     * <p>Accepted formats:</p>
     * <ul>
     *   <li>OpenAlex ID: {@code W2741809807}</li>
     *   <li>Plain DOI:   {@code 10.1038/s41586-021-03819-2}</li>
     *   <li>DOI URL:     {@code https://doi.org/10.1038/s41586-021-03819-2}</li>
     * </ul>
     *
     * @param args OpenAlex ID or DOI
     */
    @Action("getWork")
    public ActionResult getWork(String args) {
        String id = parseFirstArgument(args);
        if (id == null || id.isBlank()) return new ActionResult(false, "Work ID or DOI is required");
        String url = resolveWorkUrl(id.trim());
        try {
            String body = get(url);
            JSONObject work = new JSONObject(body);
            return new ActionResult(true, formatWork(work, true));
        } catch (Exception e) {
            logger.log(Level.WARNING, "OpenAlex getWork failed for: " + id, e);
            return new ActionResult(false, "Failed to get work: " + e.getMessage());
        }
    }

    /**
     * Returns the open-access PDF URL for a paper identified by OpenAlex ID or DOI.
     *
     * <p>Tries, in order: {@code primary_location.pdf_url},
     * {@code primary_location.landing_page_url}, then the first entry in
     * {@code open_access.oa_url}. Returns failure if no open-access URL is found.</p>
     *
     * @param args OpenAlex ID or DOI (same formats as {@code getWork})
     */
    @Action("getPdfUrl")
    public ActionResult getPdfUrl(String args) {
        String id = parseFirstArgument(args);
        if (id == null || id.isBlank()) return new ActionResult(false, "Work ID or DOI is required");
        String url = resolveWorkUrl(id.trim());
        try {
            String body = get(url);
            JSONObject work = new JSONObject(body);
            // 1. primary_location.pdf_url
            JSONObject loc = work.optJSONObject("primary_location");
            if (loc != null) {
                String pdf = loc.optString("pdf_url", null);
                if (pdf != null && !pdf.isBlank()) return new ActionResult(true, pdf);
                String landing = loc.optString("landing_page_url", null);
                if (landing != null && !landing.isBlank()) return new ActionResult(true, landing);
            }
            // 2. best_oa_location.pdf_url
            JSONObject bestOa = work.optJSONObject("best_oa_location");
            if (bestOa != null) {
                String pdf = bestOa.optString("pdf_url", null);
                if (pdf != null && !pdf.isBlank()) return new ActionResult(true, pdf);
            }
            // 3. open_access.oa_url
            JSONObject oa = work.optJSONObject("open_access");
            if (oa != null) {
                String oaUrl = oa.optString("oa_url", null);
                if (oaUrl != null && !oaUrl.isBlank()) return new ActionResult(true, oaUrl);
            }
            String doi = work.optString("doi", id);
            return new ActionResult(false, "No open-access URL found for: " + doi);
        } catch (Exception e) {
            logger.log(Level.WARNING, "OpenAlex getPdfUrl failed for: " + id, e);
            return new ActionResult(false, "Failed to get PDF URL: " + e.getMessage());
        }
    }

    /**
     * Searches OpenAlex authors by name, returns up to 5 results.
     *
     * @param args author name query string
     */
    @Action("searchAuthors")
    public ActionResult searchAuthors(String args) {
        String query = parseFirstArgument(args);
        if (query == null || query.isBlank()) return new ActionResult(false, "Query is required");
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BASE_URL + "/authors?search=" + encoded + "&per-page=5&mailto=" + email;
        try {
            String body = get(url);
            JSONObject root = new JSONObject(body);
            JSONArray results = root.getJSONArray("results");
            if (results.isEmpty()) return new ActionResult(true, "No authors found for: " + query);
            StringBuilder sb = new StringBuilder("OpenAlex authors for \"").append(query).append("\":\n\n");
            for (int i = 0; i < results.length(); i++) {
                JSONObject a = results.getJSONObject(i);
                sb.append(i + 1).append(". ").append(a.optString("display_name", "(no name)"));
                JSONObject inst = a.optJSONObject("last_known_institution");
                if (inst != null) sb.append(" (").append(inst.optString("display_name", "")).append(")");
                sb.append("\n   works: ").append(a.optInt("works_count", 0));
                sb.append(", cited: ").append(a.optInt("cited_by_count", 0));
                sb.append("\n   ID: ").append(a.optString("id", "")).append("\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "OpenAlex author search failed for: " + query, e);
            return new ActionResult(false, "Author search failed: " + e.getMessage());
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private ActionResult doSearchWorks(String query, int perPage) {
        return doSearchWorks(query, perPage, DEFAULT_SORT);
    }

    private ActionResult doSearchWorks(String query, int perPage, String sortFriendly) {
        if (query == null || query.isBlank()) return new ActionResult(false, "Query is required");
        String sort = resolveSort(sortFriendly);
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = BASE_URL + "/works?search=" + encoded
                + "&per-page=" + perPage
                + "&sort=" + URLEncoder.encode(sort, StandardCharsets.UTF_8)
                + "&mailto=" + email;
        try {
            String body = get(url);
            JSONObject root = new JSONObject(body);
            JSONArray results = root.getJSONArray("results");
            if (results.isEmpty()) return new ActionResult(true, "No papers found for: " + query);
            int total = root.getJSONObject("meta").optInt("count", 0);
            StringBuilder sb = new StringBuilder();
            sb.append("OpenAlex papers for \"").append(query)
              .append("\" (").append(total).append(" total, showing top ").append(results.length())
              .append(" by ").append(sortLabel(sort)).append("):\n\n");
            for (int i = 0; i < results.length(); i++) {
                sb.append(i + 1).append(". ").append(formatWork(results.getJSONObject(i), false)).append("\n\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "OpenAlex work search failed for: " + query, e);
            return new ActionResult(false, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Maps a friendly sort name to an OpenAlex {@code sort} value. Accepts {@code citations},
     * {@code relevance}, {@code newest}/{@code date}/{@code latest}, or a raw OpenAlex sort string
     * (containing {@code ':'}) which is passed through unchanged. Unknown values fall back to the default.
     */
    private static String resolveSort(String friendly) {
        if (friendly == null || friendly.isBlank()) return DEFAULT_SORT;
        String s = friendly.trim().toLowerCase();
        if (s.contains(":")) return friendly.trim();   // raw OpenAlex sort string, use as-is
        return switch (s) {
            case "citations", "citation", "cited", "cited_by_count", "most_cited" -> "cited_by_count:desc";
            case "relevance", "relevance_score", "match", "best_match"            -> "relevance_score:desc";
            case "newest", "date", "latest", "recent", "publication_date"          -> "publication_date:desc";
            case "oldest"                                                          -> "publication_date:asc";
            default -> DEFAULT_SORT;
        };
    }

    /** Human-readable label for the result-ordering header line. */
    private static String sortLabel(String openAlexSort) {
        return switch (openAlexSort) {
            case "cited_by_count:desc"   -> "citation count";
            case "relevance_score:desc"  -> "relevance (title/abstract match)";
            case "publication_date:desc" -> "publication date (newest first)";
            case "publication_date:asc"  -> "publication date (oldest first)";
            default -> openAlexSort;
        };
    }

    private String resolveWorkUrl(String id) {
        if (id.startsWith("W") && id.length() > 1 && Character.isDigit(id.charAt(1))) {
            // OpenAlex native ID: W2741809807
            return BASE_URL + "/works/" + id + "?mailto=" + email;
        }
        // DOI in any format — OpenAlex supports /works/doi:10.xxx directly
        String bare = id.startsWith("https://doi.org/") ? id.substring("https://doi.org/".length())
                    : id.startsWith("http://doi.org/")  ? id.substring("http://doi.org/".length())
                    : id;
        return BASE_URL + "/works/doi:" + bare + "?mailto=" + email;
    }

    private static String formatWork(JSONObject w, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(w.optString("title", "(no title)")).append('"');
        int year = w.optInt("publication_year", 0);
        if (year > 0) sb.append(" (").append(year).append(")");
        JSONObject loc = w.optJSONObject("primary_location");
        if (loc != null) {
            JSONObject src = loc.optJSONObject("source");
            if (src != null) sb.append(", ").append(src.optString("display_name", ""));
        }
        sb.append(", cited ").append(w.optInt("cited_by_count", 0)).append(" times\n");
        String doi = w.optString("doi", null);
        if (doi != null && !doi.isBlank()) sb.append("DOI: ").append(doi).append("\n");
        JSONObject aii = w.optJSONObject("abstract_inverted_index");
        if (aii != null) {
            String abstract_ = reconstructAbstract(aii);
            if (!abstract_.isBlank()) {
                String snippet = verbose || abstract_.length() <= 400
                        ? abstract_
                        : abstract_.substring(0, 400) + "...";
                sb.append("Abstract: ").append(snippet).append("\n");
            }
        }
        if (loc != null) {
            String oaUrl = loc.optString("pdf_url", null);
            if (oaUrl == null || oaUrl.isBlank()) oaUrl = loc.optString("landing_page_url", null);
            if (oaUrl != null && !oaUrl.isBlank()) sb.append("URL: ").append(oaUrl).append("\n");
        }
        if (verbose) {
            JSONArray topics = w.optJSONArray("topics");
            if (topics != null && !topics.isEmpty()) {
                sb.append("Topics: ");
                for (int i = 0; i < Math.min(3, topics.length()); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(topics.getJSONObject(i).optString("display_name", ""));
                }
                sb.append("\n");
            }
        }
        sb.append("OpenAlex: ").append(w.optString("id", ""));
        return sb.toString().stripTrailing();
    }

    /**
     * Reconstructs the abstract from OpenAlex's inverted index format.
     * The inverted index maps each word to its list of character positions.
     */
    private static String reconstructAbstract(JSONObject aii) {
        TreeMap<Integer, String> posToWord = new TreeMap<>();
        for (String word : aii.keySet()) {
            JSONArray positions = aii.getJSONArray(word);
            for (int i = 0; i < positions.length(); i++) {
                posToWord.put(positions.getInt(i), word);
            }
        }
        return String.join(" ", posToWord.values());
    }

    private String get(String url) throws Exception {
        logger.info("OpenAlex GET " + url);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "TuringWorkflow/1.5.0 (mailto:" + email + ")")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }
}
