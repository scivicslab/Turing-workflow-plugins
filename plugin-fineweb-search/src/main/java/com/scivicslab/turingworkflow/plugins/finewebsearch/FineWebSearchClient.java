package com.scivicslab.turingworkflow.plugins.finewebsearch;

import com.scivicslab.pojoactor.core.ActionResult;
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
 * fineWeb 検索サーバへの問い合わせ を保持する素のオブジェクト。アクターとして動かすのは FineWebSearchActor の役目であり、
 * 「アクターであること」はこのクラスの性質ではない（ActorSuffixAndOwnedActorRef_260722_oo01）。
 *
 * Actor that queries a FineWeb BM25 search server via HTTP.
 *
 * <p>Default target: {@code http://fineweb-search.user-pods:9000} (the k8s Service
 * deployed by {@code quarkus-fineweb-search/k8s/fineweb-search.yaml}).
 * Can be overridden with {@code setUrl}.</p>
 *
 * <p>The FineWeb index stores {@code id} and {@code url} fields only;
 * {@code text} is indexed for BM25 but not stored. Search results therefore
 * return ranked URLs, not text snippets. To obtain page content for an LLM,
 * pipe the URLs through {@code plugin-web}'s {@code FetchActor}.</p>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code setUrl}     — configure the server endpoint</li>
 *   <li>{@code search}     — search with default top_k=10; result stored in {@code ${result}}</li>
 *   <li>{@code searchTopK} — search with explicit top_k via JSON object</li>
 *   <li>{@code health}     — verify the server is reachable</li>
 * </ul>
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
     * Sets the FineWeb search server base URL. Trailing slash is stripped.
     *
     * <p>Expected argument: URL string, e.g. {@code "http://fineweb-search.user-pods:9000"}.</p>
     *
     * @param args the server URL
     * @return ActionResult indicating success or failure
     */
    public ActionResult setUrl(String args) {
        String url = parseFirstArgument(args);
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "URL is required");
        }
        this.serverUrl = url.replaceAll("/$", "");
        return new ActionResult(true, "FineWeb server URL set to " + this.serverUrl);
    }

    /**
     * Searches FineWeb with the default top_k=10 and stores the result in {@code ${result}}.
     *
     * <p>The result is a numbered list of ranked URLs (text is not stored in the index).
     * To obtain page content, fetch the returned URLs with {@code plugin-web}'s FetchActor.</p>
     *
     * <p>Expected argument: query string, e.g. {@code "machine learning"}.</p>
     *
     * @param args the search query
     * @return ActionResult with numbered URL list on success
     */
    public ActionResult search(String args) {
        return doSearch(parseFirstArgument(args), DEFAULT_TOP_K);
    }

    /**
     * Searches FineWeb with an explicit top_k.
     *
     * <p>Expected argument: JSON object {@code {"query": "...", "topK": 5}}.
     * Falls back to default top_k=10 if {@code topK} is absent.</p>
     *
     * @param args JSON object with {@code query} and optional {@code topK}
     * @return ActionResult with numbered URL list on success
     */
    public ActionResult searchTopK(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(trimmed);
                String query = obj.optString("query", "");
                int topK = obj.optInt("topK", DEFAULT_TOP_K);
                return doSearch(query, topK);
            } catch (Exception e) {
                return new ActionResult(false, "Invalid JSON: " + e.getMessage());
            }
        }
        return doSearch(parseFirstArgument(args), DEFAULT_TOP_K);
    }

    /**
     * Checks that the FineWeb search server is reachable and healthy.
     *
     * <p>Expected argument: ignored.</p>
     *
     * @param args unused
     * @return ActionResult with {@code {"status":"ok"}} body on success
     */
    public ActionResult health(String args) {
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
     * Converts the server's JSON response to a numbered list of ranked URLs.
     * Falls back to raw JSON if parsing fails.
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

    /**
     * ワークフローからの引数はJSONの配列で届くことがある。その先頭の要素を取り出す。
     *
     * <p>{@code IIActorRef} が同名の {@code protected} メソッドで提供しているものと同じ処理である。
     * このクラスはアクター参照を継承しない素のオブジェクトなので、自分で持つ。</p>
     *
     * @param arg 受け取った引数
     * @return 配列なら先頭の要素、そうでなければそのまま
     */
    private String parseFirstArgument(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "";
        }
        if (arg.startsWith("[")) {
            try {
                org.json.JSONArray arr = new org.json.JSONArray(arg);
                if (arr.length() > 0) {
                    return arr.getString(0);
                }
            } catch (Exception e) {
                // Not a valid JSON array
            }
        }
        return arg;
    }
}
