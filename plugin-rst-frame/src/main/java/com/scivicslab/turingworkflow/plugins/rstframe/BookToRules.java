package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one run of a book's files into sections of a use's rule file, asking a writer one question
 * at a time ({@code FramesAndSamplesFromBooks_261021_oo01}).
 *
 * <p>A workflow drives it with three steps: take {@link #nextQuestion()}; when there is one, ask the
 * writer and hand the reply to {@link #answer(String)}; when there is none, write
 * {@link #useFile()} and {@link #codeFiles()}. Every paragraph is asked about twice: its OCR
 * misreadings, then its sentences' relations. A relation answer that cannot be read is asked once
 * more. Headings, code, formulas and lists need no question and are written as they are met.</p>
 *
 * <p>Two things are recorded as comments before a paragraph's rule rather than hidden: a correction
 * not taken because it changed too much, and relations the writer did not give, in which case the
 * first sentence is the nucleus and the others elaborate it.</p>
 */
public final class BookToRules {

    private enum Phase { OCR, RELATIONS, RELATIONS_AGAIN }

    private final BookRules rules = new BookRules();
    private final List<BookText.Block> blocks;
    private final String source;
    private final String prefix;
    private String useFile;
    private final Map<String, String> codeFiles = new LinkedHashMap<>();

    private int next;                       // the next block to look at
    private int blockCount;
    private int sectionCount;
    private String heading;                 // the open section's title, or null when none is open
    private final List<String> children = new ArrayList<>();
    private final StringBuilder body = new StringBuilder();

    private BookText.Block paragraph;       // the paragraph being asked about, or null
    private int paragraphIndex;
    private Phase phase;
    private List<String> sentences;
    private String note;                    // comment lines to put before the paragraph's rule
    private String question;
    private boolean finished;

    /**
     * @param files   the markdown of consecutive files of one book, in order
     * @param sources the paths of those files, for the sections' {@code :source}
     * @param useFile the use's rule file the sections are added to
     * @param prefix  what this run's rule names start with, so runs added to one file do not clash
     */
    public BookToRules(List<String> files, List<String> sources, String useFile, String prefix) {
        this.blocks = BookText.read(files);
        this.source = String.join(" ", sources);
        this.useFile = useFile;
        this.prefix = prefix;
    }

    /** @return the next question for the writer, or {@code null} when the run is done */
    public String nextQuestion() {
        if (question != null) return question;
        while (!finished) {
            if (next >= blocks.size()) {
                closeSection();
                finished = true;
                return null;
            }
            int i = next++;
            BookText.Block b = blocks.get(i);
            switch (b.kind()) {
                case HEADING -> {
                    closeSection();
                    heading = b.text();
                }
                case CODE, FORMULA -> {
                    String name = newBlockName();
                    String fileName = name + ".txt";
                    codeFiles.put(fileName, b.text());
                    add(name, "(defrule " + name + " -> :file \"" + RuleFile.escape(fileName) + "\" :kind code-block)\n");
                }
                case LIST -> {
                    String name = newBlockName();
                    StringBuilder items = new StringBuilder();
                    StringBuilder leaves = new StringBuilder();
                    for (int n = 1; n <= b.sentences().size(); n++) {
                        items.append(' ').append(name).append('/').append(n);
                        leaves.append("(defrule ").append(name).append('/').append(n).append(" -> \"")
                              .append(RuleFile.escape(b.sentences().get(n - 1))).append("\")\n");
                    }
                    add(name, "(defrule " + name + " ->" + items + " :kind bullet)\n" + leaves);
                }
                case PARAGRAPH -> {
                    paragraph = b;
                    paragraphIndex = i;
                    phase = Phase.OCR;
                    note = "";
                    question = rules.ocrPrompt(b.text(), neighbour(i - 1), neighbour(i + 1));
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
                String name = newBlockName();
                add(name, note + rules.paragraphRules(name, sentences, relations));
                paragraph = null;
                question = null;
            }
        }
    }

    /** @return the use's rule file with the sections added so far */
    public String useFile() {
        return useFile;
    }

    /** @return the code and formula blocks, by the file name their rules give, to be written beside the use's rule file */
    public Map<String, String> codeFiles() {
        return Map.copyOf(codeFiles);
    }

    private String neighbour(int i) {
        return i >= 0 && i < blocks.size() && blocks.get(i).kind() == BookText.Kind.PARAGRAPH ? blocks.get(i).text() : "";
    }

    private String newBlockName() {
        return prefix + "-b" + (++blockCount);
    }

    private void add(String name, String rules) {
        if (heading == null) heading = "(before the first heading)";
        children.add(name);
        body.append(rules);
    }

    private void closeSection() {
        if (heading != null && !children.isEmpty()) {
            useFile = rules.addSection(useFile, prefix + "-s" + (++sectionCount), heading, source, children, body.toString());
        }
        heading = null;
        children.clear();
        body.setLength(0);
    }
}
