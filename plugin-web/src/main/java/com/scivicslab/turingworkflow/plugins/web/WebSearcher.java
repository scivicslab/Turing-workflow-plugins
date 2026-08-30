package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.core.ActionResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Web検索 を保持する素のオブジェクト。アクターとして動かすのは WebSearchActor の役目であり、
 * 「アクターであること」はこのクラスの性質ではない（ActorSuffixAndOwnedActorRef_260722_oo01）。
 *
 * Turing Workflow actor that performs a web search via DuckDuckGo (HTML endpoint, no API key).
 *
 * <p>Logic ported from {@code quarkus-mcp-gateway}'s {@code WebSearchTool} so that deep-research
 * workflows can call search directly, without depending on the MCP gateway.</p>
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code search} - returns numbered results with title, URL and snippet (for an LLM to read)</li>
 *   <li>{@code searchUrls} - returns only the result URLs, one per line (for a fetch pipeline)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class WebSearcher {

    private static final Logger logger = Logger.getLogger(WebSearcher.class.getName());
    private static final int DEFAULT_MAX_RESULTS = 10;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Searches the web and returns numbered results (title, URL, snippet).
     *
     * @param args the search query (plain string or {@code ["query"]})
     * @return an {@link ActionResult} with the formatted results, or failure on error
     */
    public ActionResult search(String args) {
        String query = parseFirstArgument(args);
        if (query == null || query.isBlank()) {
            return new ActionResult(false, "Error: search query is required");
        }
        try {
            String html = fetchSearchHtml(query.trim());
            return new ActionResult(true, parseResults(html, DEFAULT_MAX_RESULTS));
        } catch (Exception e) {
            logger.warning("web search failed for '" + query + "': " + e.getMessage());
            return new ActionResult(false, "Error searching for '" + query + "': " + e.getMessage());
        }
    }

    /**
     * Searches the web and returns only the result URLs, one per line.
     *
     * @param args the search query (plain string or {@code ["query"]})
     * @return an {@link ActionResult} with newline-separated URLs, or failure on error
     */
    public ActionResult searchUrls(String args) {
        String query = parseFirstArgument(args);
        if (query == null || query.isBlank()) {
            return new ActionResult(false, "Error: search query is required");
        }
        try {
            String html = fetchSearchHtml(query.trim());
            return new ActionResult(true, parseUrls(html, DEFAULT_MAX_RESULTS));
        } catch (Exception e) {
            logger.warning("web search (urls) failed for '" + query + "': " + e.getMessage());
            return new ActionResult(false, "Error searching for '" + query + "': " + e.getMessage());
        }
    }

    private String fetchSearchHtml(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://html.duckduckgo.com/html/?q=" + encoded))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (compatible; turing-workflow/1.0)")
                .header("Accept-Language", "ja,en;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static Elements selectResults(Document doc) {
        Elements results = doc.select(".result");
        if (results.isEmpty()) results = doc.select(".web-result");
        return results;
    }

    private static String parseResults(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = selectResults(doc);
        if (results.isEmpty()) return "No results found.";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element result : results) {
            if (count >= maxResults) break;
            String title = text(result, ".result__title, .result__a");
            String href = extractUrl(result, ".result__url, .result__a");
            String snippet = text(result, ".result__snippet");
            if (title.isBlank() && href.isBlank()) continue;
            sb.append(count + 1).append(". ").append(title).append("\n");
            if (!href.isBlank()) sb.append("   URL: ").append(href).append("\n");
            if (!snippet.isBlank()) sb.append("   ").append(snippet).append("\n");
            sb.append("\n");
            count++;
        }
        return count == 0 ? "No results found." : sb.toString().stripTrailing();
    }

    private static String parseUrls(String html, int maxResults) {
        Document doc = Jsoup.parse(html);
        Elements results = selectResults(doc);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Element result : results) {
            if (count >= maxResults) break;
            String href = extractUrl(result, ".result__url, .result__a");
            if (href.isBlank()) continue;
            sb.append(href).append("\n");
            count++;
        }
        return sb.toString().stripTrailing();
    }

    private static String text(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        return el != null ? el.text().strip() : "";
    }

    private static String extractUrl(Element parent, String selector) {
        Element el = parent.selectFirst(selector);
        if (el == null) return "";
        String val = el.attr("href");
        if (val.contains("uddg=")) {
            int start = val.indexOf("uddg=") + 5;
            int end = val.indexOf('&', start);
            String enc = end < 0 ? val.substring(start) : val.substring(start, end);
            try {
                return URLDecoder.decode(enc, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return val.startsWith("http") ? val : "";
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
