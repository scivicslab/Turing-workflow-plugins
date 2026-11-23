package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.action.ActionResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Converts web pages into Markdown-style plain text or returns non-HTML content as-is, providing up to 5000 characters
 * of readable text from a URL.
 *
 * <ul>
 * <li>{@code fetch} - returns a successful action result containing the extracted text if the URL is reachable and the
 * request succeeds; otherwise, returns a failure result with a message describing the HTTP error, connection problem,
 * or invalid URL.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class PageFetcher {

    private static final Logger logger = Logger.getLogger(PageFetcher.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 5000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * Returns an action result containing the readable text content of the specified URL.
     *
     * The result is a success containing the extracted text up to 5000 characters if the URL is reachable and the
     * request succeeds; for HTML pages, the text is Markdown-formatted, while for non-HTML content, the raw body is
     * returned. If the URL is blank, the request fails, or the server returns an error code, the result is a failure
     * with a message describing the error.
     *
     * @param url the URL to fetch as a plain string
     * @return an action result that is a success containing the text (up to 5000 characters) if the fetch succeeded,
     * or a failure with an error message if the URL is invalid, the connection fails, or the server returns an error
     * code
     */
    public ActionResult fetch(String url) {
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "Error: url is required");
        }
        url = url.trim();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "turing-workflow/1.0 (fetch actor)")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return new ActionResult(false, "HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            }

            String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
            String text = contentType.contains("html")
                    ? extractText(response.body(), url)
                    : response.body();

            return new ActionResult(true, truncate(text, DEFAULT_MAX_LENGTH));
        } catch (Exception e) {
            logger.warning("fetch failed for " + url + ": " + e.getMessage());
            return new ActionResult(false, "Error fetching " + url + ": " + e.getMessage());
        }
    }

    private static String extractText(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);
        doc.select("script, style, nav, footer, header, aside, [role=navigation]").remove();

        Element main = doc.selectFirst("main, article, [role=main], #content, .content, #main");
        Element root = main != null ? main : doc.body();
        if (root == null) return doc.text();

        StringBuilder sb = new StringBuilder();
        for (Element block : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td,th")) {
            String tag = block.tagName();
            String t = block.text().trim();
            if (t.isEmpty()) continue;
            if (tag.startsWith("h")) {
                sb.append("#".repeat(tag.charAt(1) - '0')).append(" ").append(t).append("\n\n");
            } else if ("pre".equals(tag)) {
                sb.append("```\n").append(block.wholeText().trim()).append("\n```\n\n");
            } else if ("li".equals(tag)) {
                sb.append("- ").append(t).append("\n");
            } else {
                sb.append(t).append("\n\n");
            }
        }
        return sb.isEmpty() ? root.text() : sb.toString().stripTrailing();
    }

    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "\n[truncated " + text.length() + " chars total]";
    }

}
