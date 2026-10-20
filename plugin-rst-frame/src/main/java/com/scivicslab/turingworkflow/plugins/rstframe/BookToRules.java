package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one sub-section of a book into one sample: a rule file of the sub-section's sentences with
 * their relations, under the name of the sub-section's structure
 * ({@code FramesAndSamplesFromBooks_261021_oo01}).
 *
 * <p>A workflow drives it with three steps: take {@link #nextQuestion()}; when there is one, ask the
 * writer and hand the reply to {@link #answer(String)}; when there is none, write
 * {@link #sampleFile()} and {@link #codeFiles()}. Every paragraph is asked about twice: its OCR
 * misreadings, then its sentences' relations. A relation answer that cannot be read is asked once
 * more. Code, formulas and lists need no question. Last, the writer is asked in one sentence what
 * the sub-section does for its reader; that is the sample's {@code use} line.</p>
 *
 * <p>The sample file begins with three comment lines that are all a catalogue of samples and frames
 * needs: {@code use} (when a frame made from it is for), {@code source} (the book's file and
 * heading), and {@code shape} (each block's relations, in order, read off the rules).</p>
 *
 * <p>Two things are recorded as comments before a paragraph's rule rather than hidden: a correction
 * not taken because it changed too much, and relations the writer did not give, in which case the
 * first sentence is the nucleus and the others elaborate it.</p>
 */
public final class BookToRules implements Questioner {

    private enum Phase { OCR, RELATIONS, RELATIONS_AGAIN, USE, JUDGE, JUDGE_AGAIN }

    /** What a run leaves: a new sample, one more source for a sample already there, or nothing. */
    public enum Outcome { NEW, SAME, SKIP }

    /** Stands for the sample's name in rules and file names until the name is known. */
    private static final String NAME = "\u0000name\u0000";
    /** How many samples nearest in shape the writer is shown when judging. */
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
     * @param file    one file of a book's markdown
     * @param source  that file's path
     * @param heading the title of the sub-section to read, as the file writes it
     * @param name    the name of the sub-section's structure, a verb phrase such as
     *                {@code explain-program-by-whole-example}; the sample and the frame made from it share it
     * @throws IllegalArgumentException when the file has no such heading, or the name is not a plain name
     */
    public BookToRules(String file, String source, String heading, String name) {
        this(file, source, heading, name, List.of());
    }

    /**
     * @param name    the sample's name, or {@code null} to have the writer name a new structure
     * @param catalog the samples already there, against which the sub-section's structure is judged;
     *                empty and with a name given, the sub-section becomes a new sample without a judgement
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

    /** @return what the run leaves; {@code null} until {@link #nextQuestion()} has returned {@code null} */
    public Outcome outcome() {
        return outcome;
    }

    /** @return the new sample's name, or the name of the sample the sub-section was found the same as; {@code null} for {@link Outcome#SKIP} */
    public String outcomeName() {
        return outcome == Outcome.SKIP ? null : name;
    }

    /** @return why nothing is left, for {@link Outcome#SKIP} */
    public String outcomeNote() {
        return outcomeNote;
    }

    /** @return the line to add to the sample found the same: the book's file and the sub-section's heading */
    public String alsoSeen() {
        return source + " / " + heading;
    }

    /** @return the next question for the writer, or {@code null} when the run is done */
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

    /** Takes the writer's reply to the question {@link #nextQuestion()} gave. */
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

    /** @return {@code null} when the reply was a judgement this run can act on, otherwise why not */
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

    /** @return the sample's rule file; complete once {@link #nextQuestion()} has returned {@code null} */
    public String sampleFile() {
        String header = ";;; use: " + (use == null ? "(not given)" : use) + "\n"
                + ";;; source: " + source + " / " + heading + "\n"
                + ";;; shape: " + String.join(" → ", shape) + "\n";
        String file = rules.newUseFile(documentId(), heading);
        file = file.substring(file.indexOf("(in-package"));
        if (children.isEmpty()) return header + file;
        return header + rules.addSection(file, "s1", heading, source, children, body.toString()).replace(NAME, nameOrDraft());
    }

    /** @return the code and formula blocks, by the file name their rules give, to be written beside the sample */
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
