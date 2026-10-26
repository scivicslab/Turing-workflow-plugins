package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms a book sub-section into a sample consisting of a rule file of the sub-section's sentences and their
 * relations, organized under the sub-section's structural name.
 *
 * <ul>
 * <li>{@link #nextQuestion()} returns the next question for the writer, or {@code null} when the run is finished.</li>
 * <li>{@link #answer(String)} takes the writer's reply to the current question.</li>
 * <li>{@link #sampleFile()} returns the completed sample's rule file.</li>
 * <li>{@link #codeFiles()} returns a map of the code and formula blocks by the file names their rules specify.</li>
 * <li>{@link #outcome()} returns whether the run produced a new sample, found an existing one, or skipped the
 * sub-section.</li>
 * <li>{@link #outcomeName()} returns the name of the sample found or created, or {@code null} if the run was
 * skipped.</li>
 * <li>{@link #outcomeNote()} returns the reason why the run was skipped.</li>
 * <li>{@link #alsoSeen()} returns the book's file path and the sub-section's heading.</li>
 * </ul>
 *
 * <p>A workflow drives this actor with a sequence of steps: call {@link #nextQuestion()}; if a question is returned,
 * call {@link #answer(String)} with the reply; when {@link #nextQuestion()} returns {@code null}, call {@link
 * #sampleFile()} and {@link #codeFiles()} to save the results.</p>
 *
 * <p>The sample file contains three header lines: {@code use} (the sub-section's purpose), {@code source} (the book's
 * file and heading), and {@code shape} (the sequence of block relations). Relations that the writer does not provide
 * are recorded as comments before the paragraph's rule, noting that the first sentence is treated as the nucleus.</p>
 *
 * <pre>{@code
 * actor: book-to-rules
 * method: nextQuestion
 * // ...
 * }</pre>
 */
public final class BookToRules implements Questioner {

    private enum Phase { OCR, RELATIONS, RELATIONS_AGAIN, USE, JUDGE, JUDGE_AGAIN }

    /**
     * The result of a run, representing whether a new sample was created, an existing sample was found to be the same,
     * or the sub-section was skipped.
     *
     * <ul><li>{@code NEW} means a new sample with a new structure name was created.</li><li>{@code SAME} means the
     * sub-section matches the structure of an existing sample, and the existing sample's name is
     * returned.</li><li>{@code SKIP} means the sub-section is a one-off or not reusable and no sample is
     * left.</li></ul>
     */
    public enum Outcome { NEW, SAME, SKIP }

    /** The placeholder used in rule and file names for the sample's name until it is determined. */
    private static final String NAME = "\u0000name\u0000";
    /** The number of samples with a similar structure shown to the writer during the judgement phase. */
    private static final int CANDIDATES = 5;
    private static final java.util.regex.Pattern JUDGEMENT =
            java.util.regex.Pattern.compile("(?im)^\\s*(same|new)\\s*:\\s*([a-z0-9]+(?:-[a-z0-9]+)*)\\s*$|^\\s*(skip)\\b");

    private final BookRules rules = new BookRules();
    private final List<BookText.Block> blocks;
    private final String source;
    private final String heading;
    private String name;
    private final List<SampleCatalog.Entry> catalog;
    private List<SampleCatalog.Entry> candidates = List.of();
    private Outcome outcome;
    private String outcomeNote = "";
    private final Map<String, String> codeFiles = new LinkedHashMap<>();

    private int next;                       // the next block to look at
    private int blockCount;
    private final List<String> children = new ArrayList<>();
    private final List<String> shape = new ArrayList<>();
    private final StringBuilder body = new StringBuilder();

    private BookText.Block paragraph;       // the paragraph being asked about, or null
    private Phase phase;
    private List<String> sentences;
    private String note;                    // comment lines to put before the paragraph's rule
    private String question;
    private String use;
    private boolean finished;

    /**
     * Initializes a process to turn a sub-section of a book into a sample by reading its markdown file and extracting
     * its structure and sentence relations.
     *
     * @param file one file of a book's markdown
     * @param source that file's path
     * @param heading the title of the sub-section to read, as the file writes it
     * @param name the name of the sub-section's structure, a verb phrase such as {@code
     * explain-program-by-whole-example}; the sample and the frame made from it share it
     *
     * @throws IllegalArgumentException if the file has no such heading, or the name is not a plain name (lower-case
     * words joined by '-')
     */
    public BookToRules(String file, String source, String heading, String name) {
        this(file, source, heading, name, List.of());
    }

    /**
     * Initializes a process to turn a book sub-section into a sample by reading its text and asking a writer to verify
     * its structure and use.
     *
     * @param file one file of a book's markdown
     * @param source that file's path
     * @param heading the title of the sub-section to read, as the file writes it
     * @param name the name of the sub-section's structure, a verb phrase such as {@code
     * explain-program-by-whole-example}; the sample and the frame made from it share it
     *
     * @param catalog the samples already there, against which the sub-section's structure is judged; empty and with a
     * name given, the sub-section becomes a new sample without a judgement
     *
     * @throws IllegalArgumentException if the file has no such heading, or the name is not a plain name (lower-case
     * words joined by '-')
     */
    public BookToRules(String file, String source, String heading, String name, List<SampleCatalog.Entry> catalog) {
        if (name != null && !isName(name)) {
            throw new IllegalArgumentException("a sample's name is lower-case words joined by '-': " + name);
        }
        this.blocks = BookText.subsection(file, heading);
        this.source = source;
        this.heading = heading.strip();
        this.name = name;
        this.catalog = List.copyOf(catalog);
    }

    private static boolean isName(String s) {
        return s.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }

    /**
     * Returns the result of the run, which is either a new sample, a source for an existing sample, or nothing.
     *
     * <p>Returns {@code null} until {@link #nextQuestion()} has returned {@code null}.
     */
    public Outcome outcome() {
        return outcome;
    }

    /**
     * Returns the name of the new sample, or the name of the existing sample if the sub-section was found to have the
     * same structure; returns {@code null} if the run resulted in {@link Outcome#SKIP}.
     */
    public String outcomeName() {
        return outcome == Outcome.SKIP ? null : name;
    }

    /**
     * Returns a string explaining why the run resulted in {@link Outcome#SKIP}, or an empty string if the outcome was
     * not {@link Outcome#SKIP}.
     */
    public String outcomeNote() {
        return outcomeNote;
    }

    /** Returns a string that is the book's file path and the sub-section's heading, separated by a slash. */
    public String alsoSeen() {
        return source + " / " + heading;
    }

    /**
     * Returns the next question for the writer, or {@code null} when the run is finished.
     *
     * <p>The question is a text string that asks the writer to correct OCR misreadings, identify sentence relations,
     * state the sub-section's purpose, or judge the sub-section's structure against existing samples. A return of
     * {@code null} signals that all blocks have been processed and the final judgement has been made.
     */
    public String nextQuestion() {
        if (question != null) return question;
        while (!finished) {
            if (next >= blocks.size()) {
                if (use == null) {
                    phase = Phase.USE;
                    question = usePrompt();
                    return question;
                }
                if (outcome == null) {
                    if (catalog.isEmpty() && name != null) {
                        outcome = Outcome.NEW;
                    } else {
                        candidates = SampleCatalog.nearest(catalog, shape, CANDIDATES);
                        phase = Phase.JUDGE;
                        question = judgePrompt();
                        return question;
                    }
                }
                finished = true;
                return null;
            }
            BookText.Block b = blocks.get(next++);
            switch (b.kind()) {
                case HEADING -> { }                       // a sub-section holds none
                case CODE, FORMULA -> {
                    String block = newBlockName();
                    String fileName = NAME + "." + block + ".txt";
                    codeFiles.put(fileName, b.text());
                    add(block, "(defrule " + block + " -> :file \"" + RuleFile.escape(fileName) + "\" :kind code-block)\n",
                            b.kind() == BookText.Kind.CODE ? "code" : "formula");
                }
                case LIST -> {
                    String block = newBlockName();
                    StringBuilder items = new StringBuilder();
                    StringBuilder leaves = new StringBuilder();
                    for (int n = 1; n <= b.sentences().size(); n++) {
                        items.append(' ').append(block).append('/').append(n);
                        leaves.append("(defrule ").append(block).append('/').append(n).append(" -> \"")
                              .append(RuleFile.escape(b.sentences().get(n - 1))).append("\")\n");
                    }
                    add(block, "(defrule " + block + " ->" + items + " :kind bullet)\n" + leaves, "list");
                }
                case PARAGRAPH -> {
                    paragraph = b;
                    phase = Phase.OCR;
                    note = "";
                    question = rules.ocrPrompt(b.text(), neighbour(next - 2), neighbour(next));
                    return question;
                }
            }
        }
        return null;
    }

    /**
     * Processes the writer's reply to the question provided by {@link #nextQuestion()}.
     *
     * <p>The result of the call depends on the current phase of the run:
     * <ul>
     * <li>If the phase is {@code OCR}, the reply is used to correct any OCR misreadings of the paragraph; if the
     * correction is too large a change, the original paragraph text is used and a note is added to the rules.</li>
     * <li>If the phase is {@code RELATIONS} or {@code RELATIONS_AGAIN}, the reply is parsed into a list of relations;
     * if parsing fails, the first sentence is treated as the nucleus and a note is added to the rules.</li>
     * <li>If the phase is {@code USE}, the first non-blank line of the reply is taken as the sub-section's
     * purpose.</li>
     * <li>If the phase is {@code JUDGE} or {@code JUDGE_AGAIN}, the reply is parsed to determine if the sub-section's
     * structure is the same as an existing sample, a new structure, or should be skipped.</li>
     * </ul>
     *
     * <p>Throws an {@link IllegalStateException} if no question is currently open.
     */
    public void answer(String reply) {
        if (question == null) throw new IllegalStateException("no question is open");
        String r = reply == null ? "" : reply.strip();
        switch (phase) {
            case OCR -> {
                if (rules.correctionAccepted(paragraph.text(), r)) {
                    sentences = BookText.sentences(r);
                } else {
                    sentences = paragraph.sentences();
                    note = ";; OCR correction not taken: the writer's version changed too much\n";
                }
                phase = Phase.RELATIONS;
                question = rules.relationPrompt(sentences);
            }
            case RELATIONS, RELATIONS_AGAIN -> {
                List<String> relations;
                try {
                    relations = rules.parseRelations(r, sentences.size());
                } catch (IllegalArgumentException e) {
                    if (phase == Phase.RELATIONS) {
                        phase = Phase.RELATIONS_AGAIN;
                        return;                                  // the same question once more
                    }
                    relations = new ArrayList<>();
                    for (int n = 0; n < sentences.size(); n++) relations.add(n == 0 ? "nucleus" : "elaboration-additional");
                    note += ";; relations not given (" + e.getMessage() + "): the first sentence is taken as the nucleus\n";
                }
                String block = newBlockName();
                add(block, note + rules.paragraphRules(block, sentences, relations), String.join("+", relations));
                paragraph = null;
                question = null;
            }
            case USE -> {
                String line = r.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
                use = line.isEmpty() ? "(not given)" : line;
                question = null;
            }
            case JUDGE, JUDGE_AGAIN -> {
                String why = judge(r);
                if (why == null) {
                    question = null;
                } else if (phase == Phase.JUDGE) {
                    phase = Phase.JUDGE_AGAIN;                   // the same question once more
                } else {
                    outcome = Outcome.SKIP;
                    outcomeNote = "the judgement could not be read: " + why;
                    question = null;
                }
            }
        }
    }

    /**
     * Returns a string describing why the writer's judgement could not be processed, or {@code null} if the judgement
     * was successfully applied to the outcome.
     *
     * <p>If the reply does not match the expected format of {@code same: <name>}, {@code new: <name>}, or {@code
     * skip}, it returns a message explaining the format error. If the reply is {@code skip}, it returns {@code null}
     * and sets the outcome to {@link Outcome#SKIP}. If the reply is {@code same: <name>}, it returns {@code null} and
     * sets the outcome to {@link Outcome#SAME} if the name exists in the catalog, otherwise it returns a message
     * stating the name is not a sample. If the reply is {@code new: <name>}, it returns {@code null} and sets the
     * outcome to {@link Outcome#NEW}.</p>
     */
    private String judge(String reply) {
        java.util.regex.Matcher m = JUDGEMENT.matcher(reply);
        if (!m.find()) return "no line of the form 'same: <name>', 'new: <name>' or 'skip'";
        if (m.group(3) != null) {
            outcome = Outcome.SKIP;
            outcomeNote = "the writer judged the structure not reusable";
            return null;
        }
        String named = m.group(2);
        boolean known = catalog.stream().anyMatch(e -> e.name().equals(named));
        if (m.group(1).equalsIgnoreCase("same")) {
            if (!known) return "'" + named + "' is not a sample";
            outcome = Outcome.SAME;
            name = named;
            return null;
        }
        if (name == null) {
            if (known) return "'" + named + "' is already a sample's name";
            name = named;
        }
        outcome = Outcome.NEW;
        return null;
    }

    private String judgePrompt() {
        StringBuilder sb = new StringBuilder("A sub-section of a book was read. What it does for its reader:\n")
                .append(use).append("\nIts blocks, each with its sentences' relations (nucleus first):\n")
                .append(String.join(" → ", shape)).append("\n\n");
        if (candidates.isEmpty()) {
            sb.append("No sample is kept yet.\n\n");
        } else {
            sb.append("Samples already kept, the nearest in shape first:\n");
            for (SampleCatalog.Entry e : candidates) {
                sb.append("- ").append(e.name()).append(": ").append(e.use()).append(" | ").append(String.join(" → ", e.shape())).append('\n');
            }
            sb.append('\n');
        }
        sb.append("Is this sub-section built the same structure as one of the samples, so that one frame would serve both? "
                + "Topics do not matter; what each part does for the reader does. Answer with one line:\n"
                + "same: <the sample's name>   if it is the same structure as that sample;\n"
                + "new: <a name>   if it is a new structure that would serve to explain other material too; the name says what "
                + "the structure does for the reader, in lower-case words joined by '-', and names no subject of the book "
                + "(no language, no data structure, no feature): e.g. show-whole-then-explain-each-part, list-options-then-recommend, "
                + "derive-cost-from-procedure;\n"
                + "skip   if it is a new structure that would not serve again (a one-off, a list of references, an aside).\n"
                + "Write that line and nothing else.");
        return sb.toString();
    }

    /**
     * Returns a string containing the sample's rule file, including a header with the use, source, and shape, and the
     * rules for each block.
     *
     * <p>The result is complete only once {@link #nextQuestion()} has returned {@code null}.</p>
     */
    public String sampleFile() {
        String header = ";;; use: " + (use == null ? "(not given)" : use) + "\n"
                + ";;; source: " + source + " / " + heading + "\n"
                + ";;; shape: " + String.join(" → ", shape) + "\n";
        String file = rules.newUseFile(documentId(), heading);
        file = file.substring(file.indexOf("(in-package"));
        if (children.isEmpty()) return header + file;
        return header + rules.addSection(file, "s1", heading, source, children, body.toString()).replace(NAME, nameOrDraft());
    }

    /**
     * Returns a map of the code and formula blocks found in the sub-section, where the keys are the file names
     * assigned to their rules.
     */
    public Map<String, String> codeFiles() {
        Map<String, String> out = new LinkedHashMap<>();
        codeFiles.forEach((k, v) -> out.put(k.replace(NAME, nameOrDraft()), v));
        return Map.copyOf(out);
    }

    private String nameOrDraft() {
        return name == null ? "draft" : name;
    }

    private String usePrompt() {
        StringBuilder sb = new StringBuilder("Below is one sub-section of a book, \"").append(heading).append("\".\n\n");
        for (BookText.Block b : blocks) {
            if (b.kind() == BookText.Kind.HEADING) continue;
            sb.append(b.kind() == BookText.Kind.CODE || b.kind() == BookText.Kind.FORMULA ? "```\n" + b.text() + "\n```" : b.text())
              .append("\n\n");
        }
        sb.append("In one sentence, say what this sub-section does for its reader, in words that do not depend on its "
                + "subject, so the sentence also fits another sub-section built the same way: e.g. \"Shows a short program "
                + "whole, then explains each concept it uses: where it is written, what it is, what it requires.\" "
                + "Write that sentence and nothing else.");
        return sb.toString();
    }

    private String documentId() {
        StringBuilder id = new StringBuilder();
        for (String part : nameOrDraft().split("-")) id.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return id + "Sample";
    }

    private String neighbour(int i) {
        return i >= 0 && i < blocks.size() && blocks.get(i).kind() == BookText.Kind.PARAGRAPH ? blocks.get(i).text() : "";
    }

    private String newBlockName() {
        return "b" + (++blockCount);
    }

    private void add(String block, String blockRules, String blockShape) {
        children.add(block);
        shape.add(blockShape);
        body.append(blockRules);
    }
}
