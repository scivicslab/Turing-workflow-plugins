package com.scivicslab.turingworkflow.plugins.rstframe;

/**
 * An interface for a workflow to interact with a writer by posing a sequence of questions and receiving replies.
 *
 * <ul>
 * <li>{@link #nextQuestion()} returns the next question to be posed to the writer, or {@code null} if no more
 * questions remain.</li>
 * <li>{@link #answer(String)} accepts the writer's response to the most recent question.</li>
 * </ul>
 *
 * <p>A workflow uses this to drive a writing process:
 * <pre>{@code
 * while (question = questioner.nextQuestion() != null) {
 *     String reply = askWriter(question);
 *     questioner.answer(reply);
 * }
 * }</pre>
 */
public interface Questioner {

    /** Returns the next question to be posed to the writer, or {@code null} if no more questions remain. */
    String nextQuestion();

    /** Records the writer's reply to the question provided by {@link #nextQuestion()}. */
    void answer(String reply);
}
