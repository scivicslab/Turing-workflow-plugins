package com.scivicslab.turingworkflow.plugins.rstframe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * The work of {@code fill-frame.yaml} ({@code WorkflowFromFrame_261020_oo01}): a rule file's blanks taken one at a
 * time, each asked of a writer and filled with the reply. The rule file itself stays in the workflow's state and is
 * handed in and back whole; this object remembers only the blank taken last and the examples found for it, so the
 * steps that follow {@link #next} are given no blank. {@link FrameFiller} does the work on text.
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
     * Takes the next blank of the rule file.
     *
     * @return {@code {name, instruction, sample, frame, codeKind, include, kind}}, {@code kind} being {@code include},
     *         {@code frame}, {@code code}, {@code items} or {@code sentence}
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
     * Finds, for the blank taken, the paragraphs of the source books that play the same part ({@link FrameExamples},
     * reading the frames under {@code framesRoot} and the edge lists in the {@code links/} beside it). None is found
     * when there is no library, or the frame is not in it.
     *
     * @param request    the frame being filled
     * @param framesRoot the library's {@code frames/} directory; blank takes the parent of {@code frameDir} when that
     *                   directory is named {@code frames}, and else none
     * @param works      the directory the books' paths are under; blank takes {@code ~/works}
     * @return how many were found
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
     * What the writer is asked for the blank taken: a frame blank is asked for the list of items; a code blank for
     * code; a list blank for its items; any other blank for one piece of text, shown the examples found.
     *
     * @param names the names of the items of the copy the blank is in, one per line
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

    /** @return the rule file with the reply written into the blank taken */
    public String fill(String file, String reply) {
        FrameFiller.Blank b = taken();
        if (file == null || blank(reply)) throw new IllegalArgumentException("the rule file and a reply are required");
        return filler.fill(file, b.name(), reply);
    }

    /** @return the rule file with the blank taken replaced by one copy of the child frame */
    public String include(String file, String frameText) {
        FrameFiller.Blank b = taken();
        if (file == null || frameText == null) throw new IllegalArgumentException("the rule file and the child frame are required");
        return filler.include(file, b.name(), frameText);
    }

    /** @return the rule file with the reply's items, one per line, written into the list blank taken */
    public String fillItems(String file, String reply) {
        FrameFiller.Blank b = taken();
        if (file == null || reply == null) throw new IllegalArgumentException("the rule file and a reply are required");
        return filler.fillList(file, b.name(), filler.parseItems(reply));
    }

    /**
     * Turns the frame blank taken into one copy of its sub-frame per item the reply names.
     *
     * @return {@code {file, names}}: the rule file, and the items' names one per line, for the prompts of the copies' blanks
     */
    public String expand(String file, String frameText, String reply, String maxItems) {
        FrameFiller.Blank b = taken();
        if (file == null || frameText == null || reply == null) throw new IllegalArgumentException("the rule file, the sub-frame and a reply are required");
        List<String> names = filler.parseNames(reply, number(maxItems, 5, "maxItems"));
        if (names.isEmpty()) throw new IllegalStateException("the reply named no item");
        return new JSONObject().put("file", filler.expand(file, b.name(), frameText, names)).put("names", String.join("\n", names)).toString();
    }

    /**
     * Writes the code in a reply into the code blank taken: the rule names a file beside {@code out}.
     *
     * @return {@code {file, code, path}}: the rule file, the code, and where the code's file is to be written
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
