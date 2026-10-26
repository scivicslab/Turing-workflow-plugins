package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.concurrent.ExecutionException;

import org.json.JSONObject;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Populates the rule files for a book by identifying blanks in the text and filling them with structured data about
 * concepts.
 *
 * <ul>
 * <li>{@code next} takes a {@code {file}} and returns a JSON message containing {@code {name, instruction, sample,
 * frame, codeKind, include, kind}} for the next blank to be filled.</li>
 * <li>{@code examples} takes {@code {request, framesRoot, frameDir, works, max}} and returns a list of examples for a
 * prompt.</li>
 * <li>{@code prompt} takes {@code {file, subject, names, language, maxItems}} and returns a text prompt.</li>
 * <li>{@code fill} takes {@code {file, reply}} and returns the updated rule file.</li>
 * <li>{@code include} takes {@code {file, frameText}} and returns the updated rule file including a child frame.</li>
 * <li>{@code fillItems} takes {@code {file, reply}} and returns the updated rule file with a filled list.</li>
 * <li>{@code expand} takes {@code {file, frameText, reply, maxItems}} and returns a JSON message containing {@code
 * {file, names}}.</li>
 * <li>{@code fillCode} takes {@code {file, reply, out}} and returns a JSON message containing {@code {file, code,
 * path}}.</li>
 * </ul>
 *
 * <pre>{@code
 * - actor: filler
 *   method: next
 *   arguments: {file: 'jexl:state.getString("cur")'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: blank, value: "jexl:result"}
 * # ... ask the writer, keep the reply ...
 * - actor: filler
 *   method: fill
 *   arguments: {file: 'jexl:state.getString("cur")', reply: 'jexl:state.getString("reply")'}
 * - actor: this
 *   method: putJson
 *   arguments: {path: cur, value: "jexl:result"}
 * }</pre>
 *
 * <p>An action fails and returns a failure message if the file cannot be processed or the requested data is
 * missing.</p>
 */
public class FrameFillingIIAR extends IIActorRef<FrameFilling> {

    public FrameFillingIIAR(String actorName, IIActorSystem system) {
        super(actorName, new FrameFilling(FileGate.of(system)), system);
    }

    /**
     * Returns an action result that is a success if a new blank was found, containing a JSON message with {@code
     * name}, {@code instruction}, {@code sample}, {@code frame}, {@code codeKind}, {@code include}, and {@code kind};
     * otherwise, it is a failure with a message stating that the next blank could not be taken.
     *
     * @param args a JSON object containing {@code file} which is the path to the rule file.
     */
    @Action("next")
    public ActionResult next(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not take the next blank", f -> f.next(opt(a, "file")));
    }

    /**
     * Returns a JSON object containing a set of examples for a prompt, with the keys {@code name}, {@code
     * instruction}, {@code sample}, {@code frame}, {@code codeKind}, {@code include}, and {@code kind}.
     *
     * @param args a JSON string with the following keys:
     * {@code request}: the search request;
     * {@code framesRoot}: the root directory for frames;
     * {@code frameDir}: the directory for frames;
     * {@code works}: the list of works to search;
     * {@code max}: the maximum number of examples to return.
     *
     * @return a success result with a JSON object containing the examples, or a failure result if the examples could
     * not be found.
     */
    @Action("examples")
    public ActionResult examples(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not find examples",
                f -> f.examples(opt(a, "request"), opt(a, "framesRoot"), opt(a, "frameDir"), opt(a, "works"), opt(a, "max")));
    }

    /**
     * Returns a string that is a prompt for a local LLM, constructed from a rule file, a subject, a list of names, a
     * language, and a limit on the number of items.
     *
     * @param args a JSON object containing:
     * {@code file}: the path to the rule file;
     * {@code subject}: the subject to be used in the prompt;
     * {@code names}: the names to be included;
     * {@code language}: the language for the prompt;
     * {@code maxItems}: the maximum number of items to include.
     *
     * @return an action result that is a success if the prompt was built, with the prompt text as the message;
     * otherwise, a failure with a message describing the error.
     */
    @Action("prompt")
    public ActionResult prompt(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not build the prompt",
                f -> f.prompt(opt(a, "file"), opt(a, "subject"), opt(a, "names"), opt(a, "language"), opt(a, "maxItems")));
    }

    /**
     * Writes the reply to a blank in a rule file.
     *
     * @param args a JSON object containing {@code file} (the path to the rule file) and {@code reply} (the text to be
     * written into the blank).
     *
     * @return a success result if the blank was filled, or a failure result with a message describing the error if the
     * file could not be filled.
     */
    @Action("fill")
    public ActionResult fill(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not fill the blank", f -> f.fill(opt(a, "file"), opt(a, "reply")));
    }

    /**
     * Includes a child frame's text into the specified rule file.
     *
     * @param args a JSON object containing {@code file} (the path to the rule file) and {@code frameText} (the text to
     * include).
     *
     * @return a successful action result with the updated rule file as the message, or a failed result if the
     * inclusion could not be performed.
     */
    @Action("include")
    public ActionResult include(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not include the child frame", f -> f.include(opt(a, "file"), opt(a, "frameText")));
    }

    /**
     * Updates a list of items in a rule file with a reply.
     *
     * Returns a success result if the rule file is updated, or a failure result with a message describing the error if
     * the update could not be performed.
     *
     * @param args a JSON string containing {@code file} (the path to the rule file) and {@code reply} (the text to be
     * inserted into the list).
     *
     * @return a success result if the list was updated, or a failure result if the update failed.
     */
    @Action("fillItems")
    public ActionResult fillItems(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not fill the list", f -> f.fillItems(opt(a, "file"), opt(a, "reply")));
    }

    /**
     * Returns an action result that is a success if the list of concepts is expanded and contains a JSON message with
     * {@code file} and {@code names}, or a failure if the expansion could not be performed.
     *
     * @param args a JSON string containing:
     * <ul>
     * <li>{@code file}: the path to the rule file to be updated</li>
     * <li>{@code frameText}: the text of the frame to expand</li>
     * <li>{@code reply}: the text to be inserted into the file</li>
     * <li>{@code maxItems}: the maximum number of items to expand to, including the end</li>
     * </ul>
     *
     * @return an action result that is a success with a message containing the updated {@code file} and the {@code
     * names} of the expanded concepts, or a failure if the expansion could not be performed.
     */
    @Action("expand")
    public ActionResult expand(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not expand the list",
                f -> f.expand(opt(a, "file"), opt(a, "frameText"), opt(a, "reply"), opt(a, "maxItems")));
    }

    /**
     * Writes a code block to a file.
     *
     * Returns an action result that is a success with a message containing {@code {file, code, path}} if the file was
     * written, or a failure if the write could not be performed.
     *
     * @param args a JSON object containing:
     * file: the path to the file to be written
     * reply: the text of the code to write
     * out: the path to the output file or directory
     */
    @Action("fillCode")
    public ActionResult fillCode(String args) {
        JSONObject a = new JSONObject(args);
        return perform("could not write the code", f -> f.fillCode(opt(a, "file"), opt(a, "reply"), opt(a, "out")));
    }

    /**
     * Returns the value associated with the key as a string, or {@code null} if the key is not present or the value is
     * null.
     */
    private static String opt(JSONObject a, String key) {
        return a.isNull(key) ? null : a.get(key).toString();
    }

    private ActionResult perform(String failure, QuestionWorkIIAR.Step<FrameFilling> step) {
        try {
            return new ActionResult(true, ask(f -> {
                try {
                    return step.on(f);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }).get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ActionResult(false, failure + ": interrupted");
        } catch (ExecutionException e) {
            return new ActionResult(false, failure + ": " + QuestionWorkIIAR.reason(e));
        }
    }
}
