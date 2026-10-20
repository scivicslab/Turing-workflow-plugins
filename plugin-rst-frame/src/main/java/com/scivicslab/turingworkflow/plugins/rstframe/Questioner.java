package com.scivicslab.turingworkflow.plugins.rstframe;

/**
 * Work done by asking a writer one question at a time: a workflow takes {@link #nextQuestion()},
 * asks the writer, and hands the reply to {@link #answer(String)}, until there is no question left
 * ({@code FramesAndSamplesFromBooks_261021_oo01}). What the work produces is read from the
 * implementing class once it is done.
 */
public interface Questioner {

    /** @return the next question for the writer, or {@code null} when the work is done */
    String nextQuestion();

    /** Takes the writer's reply to the question {@link #nextQuestion()} gave. */
    void answer(String reply);
}
