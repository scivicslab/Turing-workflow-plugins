package com.scivicslab.turingworkflow.plugins.rstframe;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Collects the items of a batch that failed and turns the batch into its outcome: the summary when every item
 * succeeded, a {@link BatchFailedException} naming each failed item and its reason otherwise.
 *
 * <p>The items that succeeded are saved by the batch as it goes, so a rerun redoes only the failed ones. The text of
 * a failure follows {@code Interpreter.apply}: the count, then {@code id: reason} for each item, joined by
 * {@code "; "}, the reason in the form {@code error: <exception class>: <message>} of the root exception, e.g.</p>
 *
 * <pre>{@code
 * 2 of 15 failed: 2.4: error: java.net.ConnectException: Connection refused; 3.1: error: ... . 13 section(s) of core listed, 2 failed
 * }</pre>
 *
 * <p>The reason is built here rather than with {@code ActionResult.errorText}, which turing-workflow 4.8.0 does not
 * have; chat-ui-with-audit-trail runs this plugin on that engine.</p>
 *
 * <p>One instance may be filled by several threads at once.</p>
 */
final class BatchFailures {

    private final List<String> failures = new ArrayList<>();

    /** Records that {@code item} failed with {@code failure}. */
    synchronized void add(String item, Throwable failure) {
        failures.add(item + ": " + reason(failure));
    }

    /**
     * Returns {@code summary} when no item failed.
     *
     * @param total the number of items the batch tried
     * @param summary the batch's one-line summary
     * @throws BatchFailedException when any item failed; its message lists them before the summary
     */
    synchronized String outcome(int total, String summary) throws BatchFailedException {
        if (failures.isEmpty()) return summary;
        List<String> sorted = new ArrayList<>(failures);
        sorted.sort(null);
        throw new BatchFailedException(failures.size() + " of " + total + " failed: " + String.join("; ", sorted) + ". " + summary);
    }

    /** Returns {@code error: <class>: <message>} of the root exception, wrappers of a call removed. */
    static String reason(Throwable failure) {
        Throwable root = failure;
        while ((root instanceof ExecutionException || root instanceof CompletionException || root instanceof InvocationTargetException)
                && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root == null) return "error: unknown error";
        String message = root.getMessage();
        return "error: " + root.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    /** A batch some of whose items failed; the message names them and their reasons. */
    static final class BatchFailedException extends Exception {
        BatchFailedException(String message) {
            super(message);
        }
    }
}
