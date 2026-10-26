package com.scivicslab.turingworkflow.plugins.openalex;

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
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Queries the OpenAlex scholarly metadata API to retrieve information about works, authors, and open-access links.
 *
 * <ul>
 * <li>{@code setEmail} — sets the contact email used in the polite pool to enable higher rate limits</li>
 * <li>{@code searchWorks} — searches for papers by keyword, returning the top 10 sorted by citation count</li>
 * <li>{@code searchWorksTopK} — searches for papers with a specific count and sort order</li>
 * <li>{@code getWork} — retrieves a single paper by its OpenAlex ID or DOI</li>
 * <li>{@code getPdfUrl} — returns the URL for an open-access PDF of a paper</li>
 * <li>{@code searchAuthors} — searches for authors by name</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <p>Example workflow step:
 * <pre>{@code
 *   - actor: openalex
 *     method: searchWorks
 *     arguments: {query: "machine learning"}
 * }</pre>
 */
public class OpenAlexClient {

    private static final Logger logger = Logger.getLogger(OpenAlexClient.class.getName());
    private static final String BASE_URL = "https://api.openalex.org";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_PER_PAGE = 10;
    /** The default ordering for search results, which sorts papers by citation count in descending order. */
    private static final String DEFAULT_SORT = "cited_by_count:desc";

    private String email = "devteam@scivicslab.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Sets the contact email used in the OpenAlex polite pool header and mailto parameter. Providing an email enables
     * higher rate limits (10 req/s → ~100 req/s).
     *
     * @param address the contact email address to be used for requests
     *
     * @return an action result that is a success if the address is non-blank, with a message containing the new email;
     * otherwise, a failure result stating that the email is required
     */
    public ActionResult setEmail(String address) {
        if (address == null || address.isBlank()) return new ActionResult(false, "Email is required");
        this.email = address.trim();
        return new ActionResult(true, "Email set to " + this.email);
    }

    /**
     * Returns an action result containing a list of the top 10 papers found by keyword, sorted by citation count.
     *
     * <p>The result message contains a numbered list of papers, each including its title, publication year, journal,
     * citation count, DOI, an abstract snippet, and an open-access URL.</p>
     *
     * <p>If the query is null or blank, the result is a failure.</p>
     *
     * @param query the keyword or phrase to search for
     */
    public ActionResult searchWorks(String query) {
        return doSearchWorks(query, DEFAULT_PER_PAGE);
    }

    /**
     * Returns a text list of OpenAlex papers matching the query, with a specified number of results and a chosen sort
     * order.
     *
     * <p>{@code sort} accepts friendly names — {@code citations} (most cited first), {@code relevance} (best
     * title/abstract match first), {@code newest} (most recent first) — or a raw OpenAlex sort string (e.g. {@code
     * publication_date:desc}).</p>
     *
     * @param query a JSON object containing the {@code query} string and optional {@code perPage} and {@code sort}
     * fields
     *
     * @param perPage the maximum number of results to return; if null, defaults to 10
     * @param sort the ordering criteria; if null or blank, defaults to most-cited first
     * @return an action result that is a success and contains a formatted list of papers if results are found, or a
     * failure message if the query is blank or the request fails
     */
    public ActionResult searchWorksTopK(String query, Integer perPage, String sort) {
        return doSearchWorks(query,
                perPage != null ? perPage : DEFAULT_PER_PAGE,
                sort != null && !sort.isBlank() ? sort : DEFAULT_SORT);
    }
    /**
     * Retrieves a single paper's metadata from OpenAlex using an OpenAlex ID or DOI.
     *
     * <p>The result is a text description containing the title, year, journal, citation count, DOI, abstract snippet,
     * and open-access URL if available.</p>
     *
     * <p>Accepted formats include:</p>
     * <ul>
     * <li>OpenAlex ID: {@code W2741809807}</li>
     * <li>Plain DOI: {@code 10.1038/s41586-021-03819-2}</li>
     * <li>DOI URL: {@code https://doi.org/10.1038/s41586-021-03819-2}</li>
     * </ul>
     *
     * @param id the OpenAlex ID or DOI of the paper
     * @return an action result that is a success and contains the formatted paper metadata, or is not a success if the
     * ID is blank or the retrieval fails
     */
    public ActionResult getWork(String id) {
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
     * Returns the open-access PDF URL or landing page URL for a paper identified by OpenAlex ID or DOI.
     *
     * <p>The result is the URL of the paper's primary PDF, its landing page, or the first entry in the open access
     * location list. If no open-access URL is found, the action result is a failure.</p>
     *
     * @param id the OpenAlex ID (e.g., {@code W2741809807}) or DOI (e.g., {@code 10.1038/s41586-021-03819-2})
     */
    public ActionResult getPdfUrl(String id) {
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
     * Returns an action result containing up to 5 authors matching the query.
     *
     * The message holds a numbered list of authors, each including their display name, last known institution, total
     * works count, total citations, and OpenAlex ID. If no authors are found, the result is a success with a message
     * stating no authors were found for the query.
     *
     * @param query the author name or name fragment to search for
     * @return an action result that is a success containing the formatted list of authors, or a failure if the query
     * is null, blank, or the search fails
     */
    public ActionResult searchAuthors(String query) {
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
     * Returns a string that is a valid OpenAlex sort parameter.
     *
     * <p>If the input contains a colon, it is returned unchanged. Otherwise, the method maps friendly names to
     * specific OpenAlex sort strings: {@code citations} (or {@code cited}, {@code most_cited}, etc.) becomes {@code
     * cited_by_count:desc}, {@code relevance} (or {@code match}, {@code best_match}, etc.) becomes {@code
     * relevance_score:desc}, and {@code newest} (or {@code date}, {@code recent}, etc.) becomes {@code
     * publication_date:desc}. The value {@code oldest} is mapped to {@code publication_date:asc}.</p>
     *
     * <p>If the input is null, blank, or does not match any known friendly name, the default sort {@code
     * cited_by_count:desc} is returned.</p>
     *
     * @param friendly the name of the sort order to use, such as "relevance" or "newest"
     * @return the corresponding OpenAlex sort string
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

    /**
     * Returns a human-readable description of the sorting criteria used in the search results header.
     *
     * @param openAlexSort the raw OpenAlex sort string used for the query
     */
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
     * Reconstructs a plain text string from OpenAlex's inverted index of an abstract.
     *
     * @param aii a JSON object representing the inverted index, where keys are words and values are arrays of
     * character positions.
     *
     * @return a string containing the reconstructed abstract with words in their correct positions.
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
