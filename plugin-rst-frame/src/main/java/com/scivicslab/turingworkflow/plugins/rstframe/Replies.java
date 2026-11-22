package com.scivicslab.turingworkflow.plugins.rstframe;

/**
 * What a model's reply says as a whole.
 *
 * <p>A question whose honest answer may be "nothing" asks the model to write the single token {@code NONE} then. Only
 * that token saves an empty result; a reply that is empty, cut off, or has no line in the form the question asked for
 * is a failure of the item, so the next run asks it again.</p>
 */
final class Replies {

    private Replies() {
    }

    /**
     * Returns whether the whole reply is the token NONE, in any case and with markup or punctuation around it
     * ({@code NONE}, {@code none.}, {@code **None**}); a sentence that contains the word is not.
     */
    static boolean isNone(String reply) {
        return reply != null && reply.replaceAll("[^A-Za-z]", "").equalsIgnoreCase("none");
    }
}
