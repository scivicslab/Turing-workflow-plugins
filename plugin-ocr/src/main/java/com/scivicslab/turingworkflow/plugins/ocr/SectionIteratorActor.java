package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Iterates through sections of markdown text extracted from OCR.
 *
 * <ul>
 * <li>{@code extractSections} takes a markdown string and returns a result containing the extracted sections.</li>
 * <li>{@code getNext} returns the next section in the sequence as a result message.</li>
 * <li>{@code setMinChars} sets the minimum character threshold for a section to be kept and returns a success
 * result.</li>
 * <li>{@code reset} restarts the iteration from the beginning and returns a success result.</li>
 * </ul>
 *
 * <p>The actor holds a {@code SectionIterator} which maintains the current position in the text between calls.</p>
 *
 * <p>One instance serves one workflow at a time.</p>
 */
public class SectionIteratorActor extends IIActorRef<SectionIterator> {

    /**
     * Creates a new actor named name within the given system to iterate through sections of markdown text.
     *
     * @param name the name used to call this actor in a workflow
     * @param system the actor system where this actor is registered
     */
    public SectionIteratorActor(String name, IIActorSystem system) {
        super(name, new SectionIterator(), system);
    }

    private SectionIterator pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The markdown text to be split into sections.
     *
     * @param markdown the text produced by OCR
     */
    public record MarkdownArgs(@NotNull String markdown) {}

    /**
     * Specifies the minimum number of characters a section must contain to be kept during extraction.
     *
     * @param minChars the smallest section kept, in characters
     */
    public record MinCharsArgs(@NotNull Integer minChars) {}

    @Action(value = "extractSections", argsType = MarkdownArgs.class)
    public ActionResult extractSections(MarkdownArgs args) {
        return pojo().extractSections(args.markdown());
    }

    /**
     * Returns the next unit of explanation from the iterator.
     *
     * @param args the argument string, which is unused
     * @return an action result containing the text of the next unit if the iterator has more elements, or a failure if
     * no more elements remain
     */
    @Action("getNext")
    public ActionResult getNext(String args) {
        return pojo().getNext();
    }

    /**
     * Sets the minimum number of characters required for a section to be kept during extraction.
     *
     * @param args an object containing the minimum number of characters as an integer
     * @return an action result that is a success if the value was set, or contains an error message otherwise
     */
    @Action(value = "setMinChars", argsType = MinCharsArgs.class)
    public ActionResult setMinChars(MinCharsArgs args) {
        return pojo().setMinChars(args.minChars());
    }

    /**
     * Resets the section iterator to its initial state.
     *
     * @param args the argument string, which is unused
     * @return an action result that is a success if the reset completed, or a failure otherwise
     */
    @Action("reset")
    public ActionResult reset(String args) {
        return pojo().reset();
    }

}