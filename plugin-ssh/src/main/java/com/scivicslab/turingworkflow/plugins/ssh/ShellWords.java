package com.scivicslab.turingworkflow.plugins.ssh;

/**
 * Makes the words of a shell command line out of text that came from a file, and refuses text that is more than the
 * word it should be.
 *
 * <p>{@code NodeInterpreter} runs {@code rm}, {@code git clone}, {@code cd} and {@code cp} on a node from the lines
 * of a document list. A line is a path and a git URL typed by a person, and the command line used to be made by
 * putting them into the text as they were, so a {@code ;}, a {@code $(...)} or a backquote in them ran what followed
 * (chat-86, HIGH 7). Now a path or URL is checked against the characters a path or URL is written with, and then
 * single-quoted, so the shell reads it as one word whatever it holds.</p>
 */
final class ShellWords {

    /** What a document path or a git URL may be written with. No space, quote, {@code $}, {@code ;}, {@code &}, {@code |}, backquote, backslash, glob or control character. */
    private static final String WORD_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~/@%+=,:-";

    private ShellWords() {
    }

    /** Returns {@code word} in single quotes, as one shell word; a single quote in it is written {@code '\''}. */
    static String quote(String word) {
        return "'" + word.replace("'", "'\\''") + "'";
    }

    /**
     * Returns the document path as a shell word. A path that starts with {@code ~/} keeps the {@code ~} outside the
     * quotes, because the shell expands it to the home directory of the user on the node only there.
     */
    static String path(String path) {
        if (path.startsWith("~/")) {
            return "~/" + quote(path.substring(2));
        }
        return quote(path);
    }

    /**
     * Refuses a document path that is not a plain path to a directory below the root or the home directory.
     *
     * <p>The path must start with {@code /} or {@code ~/}, use only the characters of {@link #WORD_CHARACTERS}, have no
     * {@code .} or {@code ..} segment, and have at least two segments, so {@code /}, {@code ~}, {@code /home} and
     * {@code ~/..} are not accepted as the directory to remove or build.</p>
     *
     * @throws IllegalArgumentException if the path is not accepted
     */
    static void checkDocumentPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("the document path is empty");
        }
        checkCharacters(path, "document path");
        if (!path.startsWith("/") && !path.startsWith("~/")) {
            throw new IllegalArgumentException("the document path must start with / or ~/");
        }
        int segments = 0;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("the document path must not have a . or .. segment");
            }
            segments++;
        }
        if (segments < 2) {
            throw new IllegalArgumentException("the document path must be a directory below the root or the home directory");
        }
    }

    /**
     * Refuses a git URL that is not written with the characters of a URL or a path, or that starts with {@code -}, which
     * {@code git} would read as an option.
     *
     * @throws IllegalArgumentException if the URL is not accepted
     */
    static void checkGitUrl(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("the git URL is empty");
        }
        checkCharacters(url, "git URL");
        if (url.startsWith("-")) {
            throw new IllegalArgumentException("the git URL must not start with -");
        }
    }

    private static void checkCharacters(String text, String what) {
        for (int i = 0; i < text.length(); i++) {
            if (WORD_CHARACTERS.indexOf(text.charAt(i)) < 0) {
                throw new IllegalArgumentException("the " + what + " has a character that is not allowed in a path or URL"
                        + " (at index " + i + ")");
            }
        }
    }
}
