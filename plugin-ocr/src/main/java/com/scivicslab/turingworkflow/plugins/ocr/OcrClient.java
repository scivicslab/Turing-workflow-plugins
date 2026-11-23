package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.action.ActionResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends PDFs to external OCR backends and returns the extracted text.
 *
 * <ul>
 * <li>{@code setMarkerUrl} configures the Marker endpoint URL.</li>
 * <li>{@code setYomitokuUrl} configures the YomiToku endpoint URL.</li>
 * <li>{@code markerOcr} downloads a PDF from a URL and returns its full content as Markdown using the Marker
 * backend.</li>
 * <li>{@code yomitokuOcr} downloads a PDF from a URL and returns its full content as plain text using the YomiToku
 * backend.</li>
 * <li>{@code ocr} selects a backend via a JSON object containing {@code url} and an optional {@code backend} ("marker"
 * or "yomitoku").</li>
 * <li>{@code downloadPdf} downloads a PDF from a URL and holds it in memory for subsequent page-by-page OCR
 * calls.</li>
 * <li>{@code markerOcrPage} returns the Markdown text of a single page from the cached PDF using a 0-based index.</li>
 * <li>{@code writeFile} writes content to a file using a JSON array where the first element is the path and the second
 * is the content.</li>
 * </ul>
 *
 * <p>The client holds a cache of the most recently downloaded PDF bytes, which is used by {@link
 * #markerOcrPage(int)}.</p>
 *
 * <p>One instance may serve several workflows at a time, but actions run one at a time.</p>
 *
 * <p>A typical use for page-by-page OCR:</p>
 * <pre>{@code
 * downloadPdf: "https://example.com/doc.pdf"
 * markerOcrPage: 0
 * markerOcrPage: 1
 * }</pre>
 *
 * <p>If an OCR backend returns a non-200 status or an empty result, the action returns a failure result. {@link
 * #markerOcrPage(int)} returns a failure if the page index is out of range or if no PDF has been cached via {@link
 * #downloadPdf(String)}.</p>
 */
public class OcrClient {

    private static final Logger logger = Logger.getLogger(OcrClient.class.getName());

    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration OCR_TIMEOUT       = Duration.ofSeconds(600);
    private static final int     MAX_YOMITOKU_PAGES = 200;

    private String markerUrl    = "http://192.168.5.13:8001";
    private String yomitokuUrl  = "http://192.168.5.17:8013";

    // PDF bytes cached by downloadPdf() for subsequent markerOcrPage() calls.
    private byte[] cachedPdf = null;

    // HTTP/1.1 required — uvicorn's multipart parser fails when Java's default
    // h2c upgrade headers (Upgrade: h2c, HTTP2-Settings) are present.
    // NORMAL redirect following required — arXiv PDF URLs return 302 to versioned URLs.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * Sets the Marker OCR server base URL.
     *
     * @param url the URL string for the Marker endpoint, e.g. {@code "http://192.168.5.13:8001"}
     * @return an action result that is a success with a message containing the new URL, or a failure if the URL is
     * null or blank
     */
    public ActionResult setMarkerUrl(String url) {
        if (url == null || url.isBlank()) return new ActionResult(false, "URL is required");
        this.markerUrl = url.replaceAll("/$", "");
        return new ActionResult(true, "Marker URL set to " + this.markerUrl);
    }

    /**
     * Sets the YomiToku OCR server base URL.
     *
     * @param url the URL string, e.g. {@code "http://192.168.5.17:8013"}
     * @return an action result that is a success with a message confirming the new URL, or a failure if the URL is
     * null or blank
     */
    public ActionResult setYomitokuUrl(String url) {
        if (url == null || url.isBlank()) return new ActionResult(false, "URL is required");
        this.yomitokuUrl = url.replaceAll("/$", "");
        return new ActionResult(true, "YomiToku URL set to " + this.yomitokuUrl);
    }

    /**
     * Downloads a PDF from the given URL and performs OCR using the Marker backend.
     *
     * <p>The action returns a success result containing the full document text as Markdown if the PDF is successfully
     * downloaded and processed; otherwise, it returns a failure result with an error message. If the URL is null or
     * blank, the action fails.</p>
     *
     * @param url the URL of the PDF to download and process
     */
    public ActionResult markerOcr(String url) {
        if (url == null || url.isBlank()) return new ActionResult(false, "PDF URL is required");
        try {
            logger.info("Downloading PDF: " + url);
            byte[] pdf = downloadBytes(url);
            logger.info("PDF downloaded (" + pdf.length + " bytes); sending to Marker");
            return callMarker(pdf);
        } catch (Exception e) {
            logger.log(Level.WARNING, "markerOcr failed for: " + url, e);
            return new ActionResult(false, "Marker OCR failed: " + e.getMessage());
        }
    }

    /**
     * Returns the plain text of a PDF by downloading it from a URL and performing OCR with the YomiToku backend.
     *
     * <p>The result is the concatenated text of all pages, with blank-line separators between them. If the OCR fails
     * or the document is empty, it returns an unsuccessful action result with an error message.</p>
     *
     * @param url the URL of the PDF to process
     */
    public ActionResult yomitokuOcr(String url) {
        if (url == null || url.isBlank()) return new ActionResult(false, "PDF URL is required");
        try {
            logger.info("Downloading PDF: " + url);
            byte[] pdf = downloadBytes(url);
            logger.info("PDF downloaded (" + pdf.length + " bytes); sending to YomiToku page-by-page");
            return callYomitoku(pdf);
        } catch (Exception e) {
            logger.log(Level.WARNING, "yomitokuOcr failed for: " + url, e);
            return new ActionResult(false, "YomiToku OCR failed: " + e.getMessage());
        }
    }

    /**
     * Performs OCR on a PDF from a given URL using the specified backend.
     *
     * <p>Returns an action result containing the full document text as a message if the OCR succeeds, or an error
     * message if the download or OCR fails. If the {@code backend} argument is absent or not equal to {@code
     * "yomitoku"} (case-insensitive), the {@link #markerOcr(String)} action is used.</p>
     *
     * @param url the URL of the PDF to download and process
     * @param backend the name of the backend to use, either {@code "marker"} or {@code "yomitoku"}
     */
    public ActionResult ocr(String url, String backend) {
        if (url == null || url.isBlank()) return new ActionResult(false, "url is required");
        return "yomitoku".equalsIgnoreCase(backend)
                ? yomitokuOcr(url)
                : markerOcr(url);
    }
    /**
     * Writes text to a file.
     *
     * <p>Returns a success result with a message containing the number of characters written if the file was written,
     * or a success result with a "skipped" message if the path is blank. If an error occurs during writing, returns a
     * failure result with the error message.</p>
     *
     * @param path the file path to write to
     * @param content the text to write to the file
     */
    public ActionResult writeFile(String path, String content) {
        if (path == null || path.isBlank()) {
            return new ActionResult(true, "writeFile: no output path; skipped");
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), content);
            logger.info("writeFile: wrote " + content.length() + " chars to " + path);
            return new ActionResult(true, "wrote " + content.length() + " chars to " + path);
        } catch (Exception e) {
            logger.log(Level.WARNING, "writeFile failed: " + path, e);
            return new ActionResult(false, "writeFile failed: " + e.getMessage());
        }
    }

    /**
     * Downloads a PDF from the given URL and stores its bytes in memory.
     *
     * <p>Returns a success result with the number of bytes downloaded if the URL is valid and the content is a PDF;
     * otherwise, returns a failure result with the error message. If the download fails or the content is not a PDF,
     * the cached data is not updated.</p>
     *
     * <p>The downloaded bytes are held in memory and persist until the next call to this action or until the actor
     * instance is destroyed. This is required before calling {@link #markerOcrPage(int)}.</p>
     *
     * <pre>{@code
     * actor: ocr_client
     * method: downloadPdf
     * arguments: "https://example.com/document.pdf"
     * }</pre>
     *
     * @param url the URL of the PDF to download
     *
     * @return an action result that is a success if the PDF was downloaded and cached, containing the byte count in
     * the message; otherwise, a failure result with the error message.
     */
    public ActionResult downloadPdf(String url) {
        if (url == null || url.isBlank()) return new ActionResult(false, "PDF URL is required");
        try {
            logger.info("Downloading PDF for page-by-page OCR: " + url);
            cachedPdf = downloadBytes(url);
            logger.info("PDF cached: " + cachedPdf.length + " bytes");
            return new ActionResult(true, "downloaded " + cachedPdf.length + " bytes");
        } catch (Exception e) {
            logger.log(Level.WARNING, "downloadPdf failed for: " + url, e);
            return new ActionResult(false, "Download failed: " + e.getMessage());
        }
    }

    /**
     * Returns the Markdown text of a single page from the cached PDF using the Marker backend.
     *
     * <p>Returns a success result containing the Markdown text of the page if the index is valid, or a failure result
     * if the page index is out of range, the page is empty, or the backend returns an error.</p>
     *
     * <p>The page index is a 0-based integer.</p>
     *
     * <p>A failure result is returned if the page index is out of range, which can be used to terminate loops in a
     * workflow.</p>
     *
     * <p>Requires {@link #downloadPdf(String)} to be called first to populate the cache.</p>
     *
     * @param pageIndex the 0-based index of the page to OCR
     * @return a success result with the Markdown text, or a failure result with an error message
     */
    public ActionResult markerOcrPage(int pageIndex) {
        if (cachedPdf == null) {
            return new ActionResult(false, "No PDF cached; call downloadPdf first");
        }
        try {
            String boundary = "----TuringMarkerPage" + pageIndex;
            byte[] body = buildMarkerPageMultipart(boundary, cachedPdf, pageIndex, "markdown");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(markerUrl + "/marker/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(OCR_TIMEOUT)
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.info("Marker page " + pageIndex + " out of range (HTTP " + resp.statusCode() + "); loop ends");
                return new ActionResult(false, "page out of range (HTTP " + resp.statusCode() + ")");
            }
            JSONObject root = new JSONObject(resp.body());
            if (!root.optBoolean("success", false)) {
                logger.info("Marker page " + pageIndex + " returned success=false; loop ends");
                return new ActionResult(false, "page out of range");
            }
            String markdown = root.optString("output", "");
            if (markdown.isBlank()) {
                logger.info("Marker page " + pageIndex + " returned empty output; loop ends");
                return new ActionResult(false, "empty page");
            }
            logger.info("Marker page " + pageIndex + " OCR: " + markdown.length() + " chars");
            return new ActionResult(true, markdown);
        } catch (Exception e) {
            logger.log(Level.WARNING, "markerOcrPage failed for page " + pageIndex, e);
            return new ActionResult(false, "Marker OCR failed: " + e.getMessage());
        }
    }

    // ── Marker ───────────────────────────────────────────────────────────────

    private ActionResult callMarker(byte[] pdf) throws Exception {
        String boundary = "----TuringOcr" + System.identityHashCode(pdf);
        byte[] body = buildMarkerMultipart(boundary, pdf, "markdown");

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(markerUrl + "/marker/upload"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(OCR_TIMEOUT)
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return new ActionResult(false, "Marker returned HTTP " + resp.statusCode());
        }
        JSONObject root = new JSONObject(resp.body());
        if (!root.optBoolean("success", false)) {
            return new ActionResult(false, "Marker reported failure: " + resp.body());
        }
        String markdown = root.optString("output", "");
        if (markdown.isBlank()) return new ActionResult(false, "Marker returned empty output");
        logger.info("Marker OCR complete: " + markdown.length() + " characters");
        return new ActionResult(true, markdown);
    }

    // Builds multipart/form-data with file + page_range + output_format (single-page request).
    private static byte[] buildMarkerPageMultipart(String boundary, byte[] pdf, int pageIndex, String outputFormat)
            throws IOException {
        String CRLF = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // file part
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"" + CRLF).getBytes());
        out.write(("Content-Type: application/pdf" + CRLF + CRLF).getBytes());
        out.write(pdf);
        out.write(CRLF.getBytes());
        // page_range part (0-based index as a string — same as exdb2 MarkerClient)
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"page_range\"" + CRLF + CRLF).getBytes());
        out.write((pageIndex + CRLF).getBytes());
        // output_format part
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"output_format\"" + CRLF + CRLF).getBytes());
        out.write((outputFormat + CRLF).getBytes());
        // closing boundary
        out.write(("--" + boundary + "--" + CRLF).getBytes());
        return out.toByteArray();
    }

    // Builds multipart/form-data with file + output_format (no page_range = full document).
    private static byte[] buildMarkerMultipart(String boundary, byte[] pdf, String outputFormat)
            throws IOException {
        String CRLF = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // file part
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"" + CRLF).getBytes());
        out.write(("Content-Type: application/pdf" + CRLF + CRLF).getBytes());
        out.write(pdf);
        out.write(CRLF.getBytes());
        // output_format part
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"output_format\"" + CRLF + CRLF).getBytes());
        out.write((outputFormat + CRLF).getBytes());
        // closing boundary
        out.write(("--" + boundary + "--" + CRLF).getBytes());
        return out.toByteArray();
    }

    // ── YomiToku ─────────────────────────────────────────────────────────────

    private ActionResult callYomitoku(byte[] pdf) throws Exception {
        List<String> pages = new ArrayList<>();
        for (int page = 0; page < MAX_YOMITOKU_PAGES; page++) {
            String boundary = "----TuringYomi" + page;
            byte[] body = buildYomitokuMultipart(boundary, pdf, page);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(yomitokuUrl + "/ocr"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .timeout(OCR_TIMEOUT)
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                logger.info("YomiToku stopped at page " + page + " (HTTP " + resp.statusCode() + ")");
                break;
            }

            JSONObject root = new JSONObject(resp.body());
            JSONArray paras = root.optJSONArray("paragraphs");
            if (paras != null) {
                List<String> pageText = new ArrayList<>();
                for (int i = 0; i < paras.length(); i++) {
                    String s = paras.getString(i).strip();
                    if (!s.isEmpty()) pageText.add(s);
                }
                if (pageText.isEmpty()) {
                    // Empty page response usually means out of range
                    logger.info("YomiToku returned empty page " + page + "; stopping");
                    break;
                }
                pages.add(String.join("\n", pageText));
            } else {
                // Fallback: text or markdown field
                String text = root.optString("text", root.optString("markdown", "")).strip();
                if (text.isEmpty()) break;
                pages.add(text);
            }
        }
        if (pages.isEmpty()) return new ActionResult(false, "YomiToku returned no text");
        String result = String.join("\n\n", pages);
        logger.info("YomiToku OCR complete: " + pages.size() + " pages, " + result.length() + " characters");
        return new ActionResult(true, result);
    }

    private static byte[] buildYomitokuMultipart(String boundary, byte[] pdf, int page)
            throws IOException {
        String CRLF = "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // file part
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"" + CRLF).getBytes());
        out.write(("Content-Type: application/pdf" + CRLF + CRLF).getBytes());
        out.write(pdf);
        out.write(CRLF.getBytes());
        // page part
        out.write(("--" + boundary + CRLF).getBytes());
        out.write(("Content-Disposition: form-data; name=\"page\"" + CRLF + CRLF).getBytes());
        out.write((page + CRLF).getBytes());
        out.write(("--" + boundary + "--" + CRLF).getBytes());
        return out.toByteArray();
    }

    // ── PDF download ─────────────────────────────────────────────────────────

    private byte[] downloadBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(DOWNLOAD_TIMEOUT)
                .header("User-Agent", "TuringWorkflow/1.5.0")
                .GET()
                .build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new Exception("PDF download failed: HTTP " + resp.statusCode() + " from " + url);
        }
        byte[] bytes = resp.body();
        if (bytes.length < 4 || bytes[0] != '%' || bytes[1] != 'P') {
            throw new Exception("Downloaded content does not look like a PDF (url=" + url + ")");
        }
        return bytes;
    }
}
