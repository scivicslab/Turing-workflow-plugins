package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.action.ActionResult;

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
 * Performs web searches through DuckDuckGo's HTML interface and returns results as text or lists of URLs.
 *
 * <ul>
 * <li>{@code search} returns up to 10 numbered results, each containing a title, a URL, and a snippet.</li>
 * <li>{@code searchUrls} returns a newline-separated list of up to 10 URLs.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <p>An action fails if the query is blank or if a network error occurs, returning an {@link ActionResult} where
 * success is false and the message holds the error details.</p>
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
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * Returns an action result containing up to 10 numbered search results, each consisting of a title, a URL, and a
     * snippet, based on the provided query.
     * An action result is a success with a message containing the formatted results if the search succeeds; otherwise,
     * it is a failure with a message describing the error.
     * If the query is null or blank, the result is a failure stating that the query is required.
     * If the search fails due to network or parsing errors, the result is a failure with the error message.
     *
     * @param query the text to search for on DuckDuckGo
     */
    public ActionResult search(String query) {
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
     * Returns an action result containing a newline-separated list of URLs found by searching the web via DuckDuckGo.
     *
     * If the query is null or blank, returns a failed action result with a message stating that the query is required.
     * If the search fails due to network or parsing errors, returns a failed action result containing the error
     * message.
     *
     * @param query the text to search for on DuckDuckGo
     */
    public ActionResult searchUrls(String query) {
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

}
