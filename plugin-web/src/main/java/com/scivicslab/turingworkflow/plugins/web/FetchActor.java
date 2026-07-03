package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

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
 * Turing Workflow actor that fetches a URL and returns its readable content.
 *
 * <p>Logic ported from {@code quarkus-mcp-gateway}'s {@code FetchTool}. HTML pages are converted
 * to a Markdown-ish plain text with the main content extracted; non-HTML content is returned
 * as-is. The result is truncated to a maximum length.</p>
 *
 * <p>Actions:</p>
 * <ul>
 *   <li>{@code fetch} - fetch the URL and return extracted text (default max 5000 characters)</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 1.0.0
 */
public class FetchActor extends IIActorRef<FetchActor> {

    private static final Logger logger = Logger.getLogger(FetchActor.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 5000;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public FetchActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Fetches a URL and returns its readable content as text.
     *
     * @param args the URL to fetch (plain string or {@code ["url"]})
     * @return an {@link ActionResult} with the extracted text, or failure on error
     */
    @Action("fetch")
    public ActionResult fetch(String args) {
        String url = parseFirstArgument(args);
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
