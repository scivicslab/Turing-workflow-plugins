package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Performs optical character recognition on PDFs using Marker or Yomitoku backends, providing actions to set server
 * URLs, run OCR on specific pages or entire documents, and write the resulting text to files.
 *
 * <ul><li><code>setMarkerUrl</code> sets the URL for the Marker OCR server.</li><li><code>setYomitokuUrl</code> sets
 * the URL for the Yomitoku OCR server.</li><li><code>markerOcr</code> runs OCR on a PDF using the Marker
 * backend.</li><li><code>yomitokuOcr</code> runs OCR on a PDF using the Yomitoku backend.</li><li><code>ocr</code>
 * runs OCR on a PDF using the specified backend.</li><li><code>writeFile</code> writes the provided content to a file
 * at the given path.</li><li><code>downloadPdf</code> downloads a PDF from the specified
 * URL.</li><li><code>markerOcrPage</code> runs Marker OCR on a specific zero-based page number.</li></ul>
 *
 * <p>The actor holds an {@code OcrClient} which maintains the connection state to the OCR services.</p>
 *
 * <p>One instance of this actor may serve a single workflow at a time.</p>
 */
public class OcrActor extends IIActorRef<OcrClient> {

    /**
     * Initializes a new OCR actor with the given name and actor system.
     *
     * @param name the name used to identify this actor in a workflow
     * @param system the actor system this actor belongs to
     */
    public OcrActor(String name, IIActorSystem system) {
        super(name, new OcrClient(), system);
    }

    private OcrClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * Represents the address of an OCR server or a PDF file.
     *
     * @param url the address of the server or the PDF file
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * Specifies the PDF to be processed and the OCR engine to use.
     *
     * @param url the address of the PDF
     * @param backend the name of the engine, either {@code yomitoku} or {@code marker}; if absent, {@code marker} is
     * used
     */
    public record OcrArgs(@NotNull String url, String backend) {}

    /**
     * The destination file path and the text content to be written.
     *
     * @param path the file path to write to; if null, no file is written
     * @param content the text to be written to the file
     */
    public record WriteArgs(String path, @NotNull String content) {}

    /**
     * Specifies the zero-based page number of a downloaded PDF to be read.
     *
     * @param page the zero-based page number
     */
    public record PageArgs(@NotNull Integer page) {}

    @Action(value = "setMarkerUrl", argsType = UrlArgs.class)
    public ActionResult setMarkerUrl(UrlArgs args) {
        return pojo().setMarkerUrl(args.url());
    }

    /**
     * Sets the URL for the Yomitoku OCR server.
     *
     * @param args a {@link UrlArgs} containing the address of the Yomitoku OCR server
     * @return an action result that is a success if the URL was updated, or a failure with a message describing the
     * error
     */
    @Action(value = "setYomitokuUrl", argsType = UrlArgs.class)
    public ActionResult setYomitokuUrl(UrlArgs args) {
        return pojo().setYomitokuUrl(args.url());
    }

    /**
     * Performs OCR on a PDF at the specified URL using the Marker backend and returns an action result.
     *
     * @param args the URL of the PDF to process
     * @return an action result indicating whether the OCR succeeded and its message
     */
    @Action(value = "markerOcr", argsType = UrlArgs.class)
    public ActionResult markerOcr(UrlArgs args) {
        return pojo().markerOcr(args.url());
    }

    /**
     * Performs OCR using the yomitoku backend on a PDF at the specified URL.
     *
     * @param args a record containing the URL of the PDF to be processed
     * @return an action result that is a success if the OCR was completed, with a message containing the result, or a
     * failure if the process could not be completed
     */
    @Action(value = "yomitokuOcr", argsType = UrlArgs.class)
    public ActionResult yomitokuOcr(UrlArgs args) {
        return pojo().yomitokuOcr(args.url());
    }

    /**
     * Performs optical character recognition on a PDF using a specified backend.
     *
     * @param args an {@link OcrArgs} containing the PDF's URL and the name of the backend to use, where a missing
     * backend defaults to {@code marker}
     *
     * @return an action result that is a success if the OCR completed, with a message containing the extracted text;
     * otherwise, a failure result indicating the error
     */
    @Action(value = "ocr", argsType = OcrArgs.class)
    public ActionResult ocr(OcrArgs args) {
        return pojo().ocr(args.url(), args.backend());
    }

    /**
     * Writes text to a file.
     *
     * @param args a record containing the file path and the text content to be written; if the path is absent, nothing
     * is written
     *
     * @return an action result that is a success if the write operation completed, or a failure if the file could not
     * be written
     */
    @Action(value = "writeFile", argsType = WriteArgs.class)
    public ActionResult writeFile(WriteArgs args) {
        return pojo().writeFile(args.path(), args.content());
    }

    /**
     * Downloads a PDF from a specified URL.
     *
     * @param args an object containing the URL of the PDF to download
     * @return an action result indicating whether the download succeeded, with a message containing the result of the
     * operation
     */
    @Action(value = "downloadPdf", argsType = UrlArgs.class)
    public ActionResult downloadPdf(UrlArgs args) {
        return pojo().downloadPdf(args.url());
    }

    /**
     * Performs OCR on a specific page of a PDF using the Marker backend.
     *
     * @param args a record containing the zero-based page number to be processed
     * @return an action result that is a success if the page was successfully processed, with the text of the OCR in
     * the message; otherwise, a failure result
     */
    @Action(value = "markerOcrPage", argsType = PageArgs.class)
    public ActionResult markerOcrPage(PageArgs args) {
        return pojo().markerOcrPage(args.page());
    }

}