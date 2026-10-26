package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * Iterates through the blanks in a rule file to ask a writer for information and records the replies. The user gets a
 * sequence of prompts for a rule file, where each prompt is tailored to the type of blank (such as a frame, a list, or
 * a code block) and the examples found in the library.
 *
 * <ul><li>{@code next(file)}: Returns a JSON string containing the name, instruction, sample, and type of the next
 * blank in the specified file.</li><li>{@code examples(request, framesRoot, frameDir, works, max)}: Searches the
 * library for book passages that serve as examples for the current blank and returns a string stating how many were
 * found.</li><li>{@code prompt(file, subject, names, language, maxItems)}: Returns a text prompt for the writer,
 * including the subject and any necessary names or language settings, tailored to the blank's kind.</li><li>{@code
 * fill(file, reply)}: Returns the rule file with the reply written into the current blank.</li><li>{@code
 * include(file, frameText)}: Returns the rule file with the current blank replaced by the provided frame
 * text.</li><li>{@code fillItems(file, reply)}: Returns the rule file with the reply's items, one per line, written
 * into the current list blank.</li><li>{@code expand(file, frameText, reply, maxItems)}: Returns a JSON string
 * containing the updated rule file and a list of item names to be used for subsequent prompts.</li><li>{@code
 * fillCode(file, reply, out)}: Returns a JSON string containing the updated rule file, the extracted code, and the
 * destination path for the code file.</li></ul>
 *
 * <p>The object remembers only the last blank taken and the examples found for it; it does not hold the rule file
 * itself, which must be passed back and forth by the user. One instance may serve several workflows at once, provided
 * each workflow manages its own rule file state.</p>
 *
 * <pre>{@code
 * actor: frame-filling
 * method: next
 * arguments:
 *   file: "path/to/rules.lisp"
 * }</pre>
 *
 * <p>If {@code next} is called without a file, or if {@code fill} is called before a blank has been taken, the action
 * fails. If {@code examples} is called with a library path that does not exist, it returns a failure message.</p>
 */
public final class FrameFilling {

    private final FileGate files;
    private final FrameFiller filler = new FrameFiller();
    private FrameFiller.Blank blank;
    private List<FrameExamples.Example> examples = List.of();

    /** The frame library, read once per frames directory. */
    private FrameExamples library;
    private Path libraryRoot;

    public FrameFilling(FileGate files) {
        this.files = files;
    }

    /**
     * Returns a JSON string describing the next blank in the rule file, including its name, instruction, sample,
     * frame, code kind, and whether it is an inclusion.
     *
     * @param file the path to the rule file
     * @return a JSON string containing the keys {@code name}, {@code instruction}, {@code sample}, {@code frame},
     * {@code codeKind}, {@code include}, and {@code kind}, where {@code kind} is one of {@code include}, {@code
     * frame}, {@code code}, {@code items}, or {@code sentence}
     *
     * @throws IllegalArgumentException if file is null
     * @throws IllegalStateException if no blank is left in the file
     */
    public String next(String file) {
        if (file == null) throw new IllegalArgumentException("the rule file is required");
        FrameFiller.Blank b = filler.next(file);
        if (b == null) throw new IllegalStateException("no blank is left");
        blank = b;
        examples = List.of();
        return new JSONObject().put("name", b.name()).put("instruction", b.instruction()).put("sample", b.sample())
                .put("frame", b.frame()).put("codeKind", b.kind()).put("include", b.include())
                .put("kind", b.isInclude() ? "include" : b.isFrame() ? "frame" : b.isCode() ? "code" : b.isList() ? "items" : "sentence")
                .toString();
    }

    /**
     * Returns a string stating the number of example paragraphs found in the library that play the same part as the
     * current blank.
     *
     * <p>The search uses the library located at {@code framesRoot} (or the parent of {@code frameDir} if the latter is
     * a {@code frames/} directory) and looks for matches in books located under {@code works}. A match is found if the
     * frame specified by {@code request} exists in the library and has corresponding examples.
     *
     * <p>If no library is found or the frame is not in the library, the result is a message stating that no examples
     * were found or that the library does not exist.
     *
     * @param request the frame being filled
     * @param framesRoot the library's {@code frames/} directory; if empty, the blank takes the parent of {@code
     * frameDir} when that directory is named {@code frames}, otherwise none
     *
     * @param frameDir the directory of the frame; if empty, the blank takes the parent of {@code frameDir} when that
     * directory is named {@code frames}
     *
     * @param works the directory containing the books' paths; if empty, the blank takes {@code ~/works}
     * @param max the maximum number of examples to find
     * @return a string stating the number of examples found, such as "3 example(s) for [blank name]", or an error
     * message
     *
     * @throws IOException if an error occurs reading the files
     */
    public String examples(String request, String framesRoot, String frameDir, String works, String max) throws IOException {
        FrameFiller.Blank b = taken();
        examples = List.of();
        Path root;
        if (!blank(framesRoot)) root = Path.of(framesRoot.strip());
        else if (!blank(frameDir) && Path.of(frameDir).getParent() != null
                && "frames".equals(String.valueOf(Path.of(frameDir).getParent().getFileName()))) root = Path.of(frameDir).getParent();
        else return "no frame library to take examples from";
        root = root.toAbsolutePath().normalize();
        Path base = (blank(works) ? Path.of(System.getProperty("user.home"), "works") : Path.of(works.strip())).toAbsolutePath().normalize();
        if (library == null || !root.equals(libraryRoot)) {
            if (!files.exists(root)) return "no frame library at " + root;
            Map<String, String> frames = new LinkedHashMap<>();
            for (Path p : files.list(root, ".lisp", Integer.MAX_VALUE)) frames.put(root.relativize(p).toString().replace('\\', '/'), files.read(p));
            // the edge lists sit beside frames/ in the library (FrameLinks_261022_oo01)
            List<String> links = new ArrayList<>();
            Path linksDir = root.getParent() == null ? null : root.getParent().resolve("links");
            if (linksDir != null && files.exists(linksDir)) for (Path p : files.list(linksDir, ".tsv", Integer.MAX_VALUE)) links.add(files.read(p));
            library = new FrameExamples(frames, links, rel -> {
                Path p = base.resolve(rel).normalize();
                try {
                    return p.startsWith(base) && files.exists(p) ? files.read(p) : null;
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            });
            libraryRoot = root;
        }
        Path req = Path.of(request.strip()).toAbsolutePath().normalize();
        String frame = req.startsWith(root) ? root.relativize(req).toString().replace('\\', '/') : req.getFileName().toString();
        examples = library.forBlank(frame, b.name(), number(max, 3, "max"));
        return examples.size() + " example(s) for " + b.name();
    }

    /**
     * Returns a text prompt that instructs a writer how to fill the current blank in a rule file.
     *
     * The prompt is a list of items if the blank is a frame or a list, a code snippet if the blank is for code, or a
     * single piece of text (accompanied by any examples found) for all other blanks.
     *
     * @param file the name of the rule file containing the blank
     * @param subject the main concept or subject being described
     * @param names the names of the items to be included in the prompt, one per line; if empty or blank, the prompt is
     * for a single piece of text
     *
     * @param language the language for the prompt, such as "Japanese"; if blank, the prompt is in the default language
     * @param maxItems the maximum number of items to include in a list prompt; if blank, 5 items are used
     */
    public String prompt(String file, String subject, String names, String language, String maxItems) {
        FrameFiller.Blank b = taken();
        if (file == null || subject == null) throw new IllegalArgumentException("the rule file and the subject are required");
        String lang = blank(language) ? "Japanese" : language.strip();
        if (b.isFrame()) return filler.listPrompt(b, subject, number(maxItems, 5, "maxItems"), lang);
        if (b.isCode()) return filler.codePrompt(file, b, subject);
        if (b.isList()) return filler.itemsPrompt(file, b, subject, lang);
        return filler.sentencePrompt(file, b, subject, blank(names) ? List.of() : List.of(names.split("\\R")), lang, examples);
    }

    /**
     * Writes the reply into the blank of the rule file that was last taken.
     *
     * @param file the path to the rule file to be modified
     * @param reply the text to write into the blank
     * @return the rule file with the reply written into the blank taken
     */
    public String fill(String file, String reply) {
        FrameFiller.Blank b = taken();
        if (file == null || blank(reply)) throw new IllegalArgumentException("the rule file and a reply are required");
        return filler.fill(file, b.name(), reply);
    }

    /**
     * Returns a string that is the rule file with the current blank replaced by the text of the child frame.
     *
     * @param file the rule file to be modified
     * @param frameText the text of the child frame to be inserted
     * @throws IllegalArgumentException if file or frameText is null
     */
    public String include(String file, String frameText) {
        FrameFiller.Blank b = taken();
        if (file == null || frameText == null) throw new IllegalArgumentException("the rule file and the child frame are required");
        return filler.include(file, b.name(), frameText);
    }

    /**
     * Writes the items in the reply, one per line, into the current list blank of the rule file.
     *
     * @param file the rule file to be modified
     * @param reply the text containing the items, one per line
     * @return the rule file with the items written into the current list blank
     */
    public String fillItems(String file, String reply) {
        FrameFiller.Blank b = taken();
        if (file == null || reply == null) throw new IllegalArgumentException("the rule file and a reply are required");
        return filler.fillList(file, b.name(), filler.parseItems(reply));
    }

    /**
     * Expands the current frame blank into multiple copies of a sub-frame, one for each item named in the reply.
     *
     * Returns a JSON string containing the name of the rule file with the expansion performed, and a list of the item
     * names (one per line) to be used for the subsequent prompts of those copies.
     *
     * Throws an {@link IllegalArgumentException} if {@code file}, {@code frameText}, or {@code reply} is null.
     *
     * Throws an {@link IllegalStateException} if the reply contains no item names.
     *
     * @param file the name of the rule file to be modified
     * @param frameText the text of the sub-frame to be inserted
     * @param reply a string containing the names of the items, one per line
     * @param maxItems the maximum number of items to parse; if blank, 5 is used
     * @return a JSON string with {@code file} (the updated rule file) and {@code names} (the list of item names)
     */
    public String expand(String file, String frameText, String reply, String maxItems) {
        FrameFiller.Blank b = taken();
        if (file == null || frameText == null || reply == null) throw new IllegalArgumentException("the rule file, the sub-frame and a reply are required");
        List<String> names = filler.parseNames(reply, number(maxItems, 5, "maxItems"));
        if (names.isEmpty()) throw new IllegalStateException("the reply named no item");
        return new JSONObject().put("file", filler.expand(file, b.name(), frameText, names)).put("names", String.join("\n", names)).toString();
    }

    /**
     * Writes the code extracted from a reply into the current code blank of a rule file and returns the updated file,
     * the code, and the destination path.
     *
     * @param file the name of the rule file to be updated
     * @param reply the text containing the code to be extracted
     * @param out the directory where the code's file should be written
     * @return a JSON string containing {@code file} (the updated rule file), {@code code} (the extracted code), and
     * {@code path} (the absolute path where the code's file is located)
     *
     * @throws IllegalArgumentException if file or reply is null, if out is blank, or if the extracted code is blank
     * @throws IllegalStateException if the reply contains no code
     */
    public String fillCode(String file, String reply, String out) {
        FrameFiller.Blank b = taken();
        if (file == null || reply == null || blank(out)) throw new IllegalArgumentException("the rule file, a reply and the output path are required");
        String code = filler.codeOf(reply);
        if (code.isBlank()) throw new IllegalStateException("the reply held no code");
        String fileName = filler.codeFileName(b.name());
        Path dir = Path.of(out.strip()).toAbsolutePath().getParent();
        return new JSONObject().put("file", filler.fillWithFile(file, b.name(), fileName)).put("code", code)
                .put("path", (dir == null ? Path.of(fileName) : dir.resolve(fileName)).toString()).toString();
    }

    private FrameFiller.Blank taken() {
        if (blank == null) throw new IllegalStateException("no blank has been taken");
        return blank;
    }

    private static int number(String s, int otherwise, String what) {
        try {
            return blank(s) ? otherwise : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " is not a number: " + s);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
