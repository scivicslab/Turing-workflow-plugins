package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Extracts text and metadata from pages of a document file.
 *
 * <ul><li>loadFile: loads a file from the given path.</li><li>nextPage: advances to the next page in the
 * document.</li><li>getPageText: returns the text content of the current page.</li><li>getPageInfo: returns the
 * metadata and structural information of the current page.</li></ul>
 *
 * <p>The actor holds the state of the current document and its position within the file.</p>
 *
 * <p>One instance serves one workflow at a time.</p>
 *
 * <p>Example workflow step:</p>
 * <pre>{@code
 * actor: kanakanji_ocr
 * method: loadFile
 * arguments:
 *   path: /path/to/book.md
 * }</pre>
 *
 * <p>If the file cannot be found or read, the actions return a failed action result.</p>
 */
public class OcrActor extends IIActorRef<OcrPages> {

    /**
     * Initializes a new actor that performs OCR on pages of a document.
     *
     * @param name the name used to call this actor in a workflow
     * @param system the actor system this actor belongs to
     */
    public OcrActor(String name, IIActorSystem system) {
        super(name, new OcrPages(), system);
    }

    private OcrPages pojo() {
        return object;
    }

    /**
     * Holds the file path from which the pages are read.
     *
     * @param path the path to the file
     */
    public record FileArgs(@NotNull String path) {}

    @Action(value = "loadFile", argsType = FileArgs.class)
    public ActionResult loadFile(FileArgs args) {
        return pojo().loadFile(args.path());
    }

    /**
     * Advances the internal page pointer to the next page in the loaded file.
     *
     * @param args the argument from the workflow
     * @return an action result containing the outcome of the operation
     */
    @Action("nextPage")
    public ActionResult nextPage(String args) {
        return pojo().nextPage();
    }

    /**
     * Returns the result of the current page's text extraction.
     *
     * @param args the argument from the workflow
     * @return the action result from the underlying {@code OcrPages} object
     */
    @Action("getPageText")
    public ActionResult getPageText(String args) {
        return pojo().getPageText();
    }

    /**
     * Returns an action result containing the information for the current page.
     *
     * @param args the workflow arguments
     * @return an action result that is a success and holds the page information in its message if the operation
     * succeeded, or is not a success if it failed
     */
    @Action("getPageInfo")
    public ActionResult getPageInfo(String args) {
        return pojo().getPageInfo();
    }

}