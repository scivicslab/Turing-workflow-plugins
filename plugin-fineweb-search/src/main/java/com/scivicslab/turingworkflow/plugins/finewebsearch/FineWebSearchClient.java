package com.scivicslab.turingworkflow.plugins.finewebsearch;

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
 * Queries a FineWeb BM25 search server via HTTP to return ranked URLs.
 *
 * <ul><li>{@code setUrl} — configures the server endpoint</li><li>{@code search} — searches with the default top_k=10
 * and returns a numbered list of URLs</li><li>{@code searchTopK} — searches with an explicit top_k and returns a
 * numbered list of URLs</li><li>{@code health} — verifies the server is reachable</li></ul>
 *
 * <p>The index stores only {@code id} and {@code url} fields; text is not returned. To obtain page content for an LLM,
 * pipe the returned URLs through {@code plugin-web}'s {@code FetchActor}.</p>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public class FineWebSearchClient {

    private static final Logger logger = Logger.getLogger(FineWebSearchClient.class.getName());
    private static final int DEFAULT_TOP_K = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private String serverUrl = "http://fineweb-search.user-pods:9000";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Sets the FineWeb search server base URL.
     *
     * Returns an ActionResult that is a success if the URL is non-blank, with a message containing the new URL;
     * otherwise returns a failure with a message stating that a URL is required.
     *
     * @param url the server URL to use
     */
    public ActionResult setUrl(String url) {
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "URL is required");
        }
        this.serverUrl = url.replaceAll("/$", "");
        return new ActionResult(true, "FineWeb server URL set to " + this.serverUrl);
    }

    /**
     * Returns an action result that is a success containing a numbered list of ranked URLs and their BM25 scores, or a
     * failure with an error message if the query is blank, the server is unreachable, or the request fails.
     *
     * <p>The list contains only URLs and scores, as the index does not store text snippets. To obtain page content,
     * pipe the returned URLs through {@code plugin-web}'s {@code FetchActor}.</p>
     *
     * @param query the search terms to look for
     * @return an ActionResult that is a success with a message containing a numbered list of URLs and scores, or a
     * failure with an error message if the query is blank, the server is unreachable, or the request fails
     */
    public ActionResult search(String query) {
        return doSearch(query, DEFAULT_TOP_K);
    }

    /**
     * Returns a numbered list of ranked URLs found via FineWeb search, using a specific maximum number of results.
     *
     * @param query the search terms to look for
     * @param topK the maximum number of results to return
     * @return an ActionResult that is a success containing a numbered list of URLs and scores, or a failure with an
     * error message if the query is blank, the server is unreachable, or the request fails
     */
    public ActionResult searchTopK(String query, Integer topK) {
        return doSearch(query, topK != null ? topK : DEFAULT_TOP_K);
    }
    /**
     * Returns an action result that is a success if the server is reachable and returns HTTP 200, with a message
     * containing the JSON string {@code {"status":"ok"}}; otherwise returns a failure with an error message.
     *
     * @return an action result that is a success with the body {@code {"status":"ok"}} if the server is reachable and
     * returns HTTP 200, and is not a success if the server is unreachable or returns a non-200 status code
     */
    public ActionResult health() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return new ActionResult(true, resp.body());
            }
            return new ActionResult(false, "Health check failed: HTTP " + resp.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "Health check interrupted");
        } catch (Exception e) {
            return new ActionResult(false, "Health check failed: " + e.getMessage());
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private ActionResult doSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return new ActionResult(false, "Query is required");
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl + "/search?query=" + encoded + "&top_k=" + topK))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return new ActionResult(false, "Search failed: HTTP " + resp.statusCode());
            }
            return new ActionResult(true, formatResults(query, resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, "Search interrupted");
        } catch (Exception e) {
            logger.log(Level.WARNING, "FineWeb search failed for: " + query, e);
            return new ActionResult(false, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Returns a string that is a numbered list of ranked URLs and their scores, or the raw JSON if parsing fails.
     *
     * The result is a string starting with a header containing the query, followed by lines in the format "index. URL
     * (score: value)", where the value is a decimal formatted to three places. If the JSON is invalid or missing the
     * results array, the result is the original JSON string.
     *
     * @param query the search query used in the header
     * @param json the raw JSON response from the server
     */
    private static String formatResults(String query, String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONArray results = root.getJSONArray("results");
            if (results.isEmpty()) {
                return "No results found for: " + query;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("FineWeb search results for \"").append(query).append("\":\n");
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                sb.append(i + 1).append(". ")
                  .append(r.optString("url", "(no url)"))
                  .append(" (score: ").append(String.format("%.3f", r.optDouble("score", 0.0)))
                  .append(")\n");
            }
            return sb.toString().stripTrailing();
        } catch (Exception e) {
            return json;
        }
    }

}
