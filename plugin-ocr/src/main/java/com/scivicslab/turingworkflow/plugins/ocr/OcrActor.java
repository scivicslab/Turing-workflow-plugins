package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
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
 * Actor that sends PDFs to external OCR backends and returns the full document text.
 *
 * <p>Two backends are supported, matching what quarkus-exdb2 uses:</p>
 * <ul>
 *   <li><b>Marker</b> ({@code 192.168.5.13:8001}) — English / math-heavy PDFs.
 *       Preserves LaTeX. Processes the full document in one multipart POST.
 *       Returns Markdown with headings, code blocks, and inline math.</li>
 *   <li><b>YomiToku</b> ({@code 192.168.5.17:8013}) — Japanese / mixed-language PDFs.
 *       No math support. Processes one page at a time; pages are concatenated.</li>
 * </ul>
 *
 * <p>Supported actions:</p>
 * <ul>
 *   <li>{@code setMarkerUrl}   — configure Marker endpoint (default: http://192.168.5.13:8001)</li>
 *   <li>{@code setYomitokuUrl} — configure YomiToku endpoint (default: http://192.168.5.17:8013)</li>
 *   <li>{@code markerOcr}     — download PDF from URL and OCR with Marker; returns Markdown</li>
 *   <li>{@code yomitokuOcr}   — download PDF from URL and OCR with YomiToku; returns plain text</li>
 *   <li>{@code ocr}           — auto-select backend via JSON: {"url":"...","backend":"marker|yomitoku"}</li>
 * </ul>
 *
 * <p>PDF protocol: the actor downloads the PDF from the given URL using HTTP GET,
 * then uploads the bytes to the OCR backend. No local filesystem access is required.</p>
 */
public class OcrActor extends IIActorRef<OcrActor> {

    private static final Logger logger = Logger.getLogger(OcrActor.class.getName());

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
            .build();

    public OcrActor(String name, IIActorSystem system) {
        super(name, null, system);
    }

    /**
     * Sets the Marker OCR server base URL.
     *
     * @param args URL string, e.g. {@code "http://192.168.5.13:8001"}
     */
    @Action("setMarkerUrl")
    public ActionResult setMarkerUrl(String args) {
        String url = parseFirstArgument(args);
        if (url == null || url.isBlank()) return new ActionResult(false, "URL is required");
        this.markerUrl = url.replaceAll("/$", "");
        return new ActionResult(true, "Marker URL set to " + this.markerUrl);
    }

    /**
     * Sets the YomiToku OCR server base URL.
     *
     * @param args URL string, e.g. {@code "http://192.168.5.17:8013"}
     */
    @Action("setYomitokuUrl")
    public ActionResult setYomitokuUrl(String args) {
        String url = parseFirstArgument(args);
        if (url == null || url.isBlank()) return new ActionResult(false, "URL is required");
        this.yomitokuUrl = url.replaceAll("/$", "");
        return new ActionResult(true, "YomiToku URL set to " + this.yomitokuUrl);
    }

    /**
     * Downloads a PDF from the given URL and OCRs it with Marker.
     *
     * <p>Sends the full PDF binary to {@code POST /marker/upload} with
     * {@code output_format=markdown}. No page_range is set, so Marker processes
     * all pages in one pass. Returns the Markdown text of the entire document.</p>
     *
     * <p>Expected argument: PDF URL string.</p>
     *
     * @param args PDF URL
     */
    @Action("markerOcr")
    public ActionResult markerOcr(String args) {
        String url = parseFirstArgument(args);
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
     * Downloads a PDF from the given URL and OCRs it with YomiToku, page by page.
     *
     * <p>Pages are fetched from index 0 upward until YomiToku returns a non-200
     * response (indicating the page is out of range). Text from all pages is
     * concatenated with blank-line separators.</p>
     *
     * <p>Expected argument: PDF URL string.</p>
     *
     * @param args PDF URL
     */
    @Action("yomitokuOcr")
    public ActionResult yomitokuOcr(String args) {
        String url = parseFirstArgument(args);
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
     * Downloads a PDF and OCRs it with the backend specified in a JSON argument.
     *
     * <p>Expected argument: JSON object {@code {"url": "...", "backend": "marker|yomitoku"}}.
     * Defaults to {@code "marker"} if {@code backend} is absent.</p>
     *
     * @param args JSON object with {@code url} and optional {@code backend}
     */
    @Action("ocr")
    public ActionResult ocr(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(trimmed);
                String url     = obj.optString("url", "");
                String backend = obj.optString("backend", "marker");
                if (url.isBlank()) return new ActionResult(false, "url is required");
                return "yomitoku".equalsIgnoreCase(backend)
                        ? yomitokuOcr(url)
                        : markerOcr(url);
            } catch (Exception e) {
                return new ActionResult(false, "Invalid JSON: " + e.getMessage());
            }
        }
        // Plain URL → default to Marker
        return markerOcr(parseFirstArgument(args));
    }

    /**
     * Writes text to a file. Accepts a JSON array {@code ["path", "content"]}. Returns success without
     * writing when path is blank, so callers can use this step unconditionally (no-op in standalone mode).
     *
     * @param args JSON array: first element is the output file path, second is the content to write
     */
    @Action("writeFile")
    public ActionResult writeFile(String args) {
        String path;
        String content;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(args == null ? "[]" : args.trim());
            path    = arr.length() > 0 ? arr.getString(0) : "";
            content = arr.length() > 1 ? arr.getString(1) : "";
        } catch (Exception e) {
            return new ActionResult(false, "writeFile: invalid args (expected [path, content]): " + e.getMessage());
        }
        if (path.isBlank()) {
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
     * Downloads a PDF from the given URL and caches it in memory for page-by-page processing.
     *
     * <p>Call this once before a loop of {@link #markerOcrPage(String)} calls.
     * The cached bytes persist for the lifetime of this actor instance.</p>
     *
     * @param args PDF URL (HTTPS supported; redirects are followed automatically)
     * @return confirmation string with the byte count
     */
    @Action("downloadPdf")
    public ActionResult downloadPdf(String args) {
        String url = parseFirstArgument(args);
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
     * OCRs a single page of the cached PDF with Marker and returns its Markdown text.
     *
     * <p>Uses {@code page_range: "N"} (0-based index) in the multipart upload — the same
     * protocol that quarkus-exdb2's {@code MarkerClient.ocrPage()} uses.</p>
     *
     * <p>Returns {@code ActionResult(false, ...)} when the page index is out of range
     * (Marker returns non-200 or {@code "success": false}), which drives the paraloop
     * exit transition in a Turing Workflow.</p>
     *
     * <p>Call {@link #downloadPdf(String)} before using this action.</p>
     *
     * @param args 0-based page index as a string
     * @return Markdown text of the requested page
     */
    @Action("markerOcrPage")
    public ActionResult markerOcrPage(String args) {
        if (cachedPdf == null) {
            return new ActionResult(false, "No PDF cached; call downloadPdf first");
        }
        String pageStr = parseFirstArgument(args);
        int pageIndex;
        try {
            pageIndex = Integer.parseInt(pageStr == null ? "0" : pageStr.trim());
        } catch (NumberFormatException e) {
            return new ActionResult(false, "Invalid page index: " + pageStr);
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
