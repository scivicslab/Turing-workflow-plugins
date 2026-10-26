package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Writes kanji-kana pairs to a file.
 *
 * <ul><li>openOutput: opens the output file at the specified path.</li><li>setPageInfo: sets the page description for
 * the subsequent pairs.</li><li>checkHiragana: validates the presence of hiragana in the provided
 * text.</li><li>writePairs: writes the provided text as kanji-kana pairs to the open file.</li><li>closeOutput: closes
 * the current output file.</li></ul>
 *
 * <p>One instance serves one workflow at a time. If the underlying writer is null, all actions fail.</p>
 */
public class PairsActor extends IIActorRef<PairWriter> {

    /**
     * Creates a new actor named {@code name} within the given {@code system} to write kanji-kana pairs to a file.
     *
     * @param name the name to register this actor under in the workflow
     * @param system the actor system in which this actor is registered
     */
    public PairsActor(String name, IIActorSystem system) {
        super(name, new PairWriter(), system);
    }

    private PairWriter pojo() {
        return object;
    }

    /**
     * @param filePath ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The path to the file where pairs are written.
     *
     * @param path the file system path for the output file
     */
    public record FileArgs(@NotNull String path) {}

    /**
     * The description of the page from which the subsequent pairs are extracted.
     *
     * @param pageInfo the page's description
     */
    public record PageInfoArgs(@NotNull String pageInfo) {}

    /**
     * The text returned by the model.
     *
     * @param response the model's answer
     */
    public record ResponseArgs(@NotNull String response) {}

    @Action(value = "openOutput", argsType = FileArgs.class)
    public ActionResult openOutput(FileArgs args) {
        return pojo().openOutput(args.path());
    }

    /**
     * Sets the page description for the subsequent pairs to be written.
     *
     * @param args the page's description
     * @return an action result that is a success if the page info was updated, or a failure if it could not be set
     */
    @Action(value = "setPageInfo", argsType = PageInfoArgs.class)
    public ActionResult setPageInfo(PageInfoArgs args) {
        return pojo().setPageInfo(args.pageInfo());
    }

    /**
     * Verifies that the provided text contains only Hiragana characters.
     *
     * @param args a record containing the text to be checked in {@code response}
     * @return an action result that is a success if the text consists solely of Hiragana, with a message describing
     * the result; otherwise, a failure result
     */
    @Action(value = "checkHiragana", argsType = ResponseArgs.class)
    public ActionResult checkHiragana(ResponseArgs args) {
        return pojo().checkHiragana(args.response());
    }

    /**
     * Writes the provided text as kanji-kana pairs to the current output file.
     *
     * @param args a record containing the text to be processed as the response
     *
     * @return an action result that is a success if the pairs were successfully written and the message holds the
     * number of pairs written, or is not a success if the writing failed
     */
    @Action(value = "writePairs", argsType = ResponseArgs.class)
    public ActionResult writePairs(ResponseArgs args) {
        return pojo().writePairs(args.response());
    }

    /**
     * Closes the output file being used by the actor.
     *
     * @param args the argument passed from the workflow (unused)
     * @return an action result indicating whether the file was closed successfully
     */
    @Action("closeOutput")
    public ActionResult closeOutput(String args) {
        return pojo().closeOutput();
    }

}