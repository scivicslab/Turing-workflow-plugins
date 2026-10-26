package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates a single rst-on-lisp rule file from a markdown book file by asking a writer to correct OCR misreadings and
 * identify how each paragraph's sentences relate to a nucleus.
 *
 * <ul><li>{@link #nextQuestion()} returns a question about OCR corrections or sentence relations, or {@code null} when
 * the file is finished.</li><li>{@link #answer(String)} accepts a reply to the current question, updating the internal
 * state of the rule file construction.</li><li>{@link #ruleFile()} returns the completed rule file as a
 * string.</li><li>{@link #codeFiles()} returns a map of filenames to the text of code and formula blocks to be saved
 * alongside the rule file.</li><li>{@link #paragraphs()} returns the total number of paragraphs in the
 * file.</li><li>{@link #asked()} returns the number of questions presented to the writer so far.</li></ul>
 *
 * <p>One instance processes one file and may be used by a single workflow. If a relation answer cannot be parsed, the
 * actor asks once more; if the second attempt fails, the first sentence is taken as the nucleus and a comment is added
 * to the rule file.</p>
 */
public final class BookFileToRules implements Questioner {

    private enum Phase { OCR, RELATIONS, RELATIONS_AGAIN }

    /**
     * Represents a single section of the book, containing its identifier, heading, the list of block names within it,
     * and the accumulated rule text.
     *
     * @param name the section identifier (e.g., {@code s1})
     * @param heading the text of the section heading
     * @param children the list of block names (e.g., {@code b1}, {@code b2}) belonging to this section
     * @param body the buffer containing the generated Lisp rules for this section
     */
    private record Section(String name, String heading, List<String> children, StringBuilder body) {
    }

    private final BookRules rules = new BookRules();
    private final List<BookText.Block> blocks;
    private final String source;
    private final String documentId;
    private final String title;
    private final List<Section> sections = new ArrayList<>();
    private final Map<String, String> codeFiles = new LinkedHashMap<>();

    private int next;
    private int blockCount;
    private BookText.Block paragraph;
    private Phase phase;
    private List<String> sentences;
    private String note;
    private String question;
    private int asked;

    /**
     * Initializes the state for converting a markdown book file into an rst-on-lisp rule file by reading the file's
     * blocks and determining the document title.
     *
     * @param file the markdown file's text
     * @param source the file's path, written into each section's {@code :source}
     * @param documentId the rule file's document id
     */
    public BookFileToRules(String file, String source, String documentId) {
        this.blocks = BookText.read(file);
        this.source = source;
        this.documentId = documentId;
        String first = null;
        for (BookText.Block b : blocks) {
            if (b.kind() == BookText.Kind.HEADING) {
                first = b.text();
                break;
            }
        }
        this.title = first != null ? first : documentId;
    }

    /** Returns the number of paragraphs in the file, where each paragraph is subject to two questions. */
    public int paragraphs() {
        return (int) blocks.stream().filter(b -> b.kind() == BookText.Kind.PARAGRAPH).count();
    }

    /** Returns the number of questions that have been posed to the writer so far. */
    public int asked() {
        return asked;
    }

    @Override
    public String nextQuestion() {
        if (question != null) return question;
        while (next < blocks.size()) {
            BookText.Block b = blocks.get(next++);
            switch (b.kind()) {
                case HEADING -> sections.add(new Section("s" + (sections.size() + 1), b.text(), new ArrayList<>(), new StringBuilder()));
                case CODE, FORMULA -> {
                    String block = newBlockName();
                    String fileName = block + ".txt";
                    codeFiles.put(fileName, b.text());
                    add(block, "(defrule " + block + " -> :file \"" + RuleFile.escape(fileName) + "\" :kind code-block)\n");
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
                    add(block, "(defrule " + block + " ->" + items + " :kind bullet)\n" + leaves);
                }
                case PARAGRAPH -> {
                    paragraph = b;
                    phase = Phase.OCR;
                    note = "";
                    question = rules.ocrPrompt(b.text(), neighbour(next - 2), neighbour(next));
                    asked++;
                    return question;
                }
            }
        }
        return null;
    }

    @Override
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
                asked++;
            }
            case RELATIONS, RELATIONS_AGAIN -> {
                List<String> relations;
                try {
                    relations = rules.parseRelations(r, sentences.size());
                } catch (IllegalArgumentException e) {
                    if (phase == Phase.RELATIONS) {
                        phase = Phase.RELATIONS_AGAIN;
                        question = rules.relationPrompt(sentences) + "\n\nYour previous answer could not be used: " + e.getMessage()
                                + ". Use only \"nucleus\" and the relation names listed above, written exactly as they are there.";
                        asked++;
                        return;
                    }
                    relations = new ArrayList<>();
                    for (int n = 0; n < sentences.size(); n++) relations.add(n == 0 ? "nucleus" : "elaboration-additional");
                    note += ";; relations not given (" + e.getMessage() + "): the first sentence is taken as the nucleus\n";
                }
                String block = newBlockName();
                add(block, note + rules.paragraphRules(block, sentences, relations));
                paragraph = null;
                question = null;
            }
        }
    }

    /**
     * Returns the rst-on-lisp rule file containing the book's title, sections, and the parsed relations for every
     * paragraph.
     *
     * <p>The file is complete only once {@link #nextQuestion()} returns {@code null}.</p>
     */
    public String ruleFile() {
        String file = ";;; " + title + ". The sentences are the book's own, with OCR misreadings corrected; only the structure\n"
                + ";;; is added: which sentence is the nucleus of a paragraph, and by which relation each other sentence joins it.\n"
                + "(in-package :rst)\n"
                + "(defdocument " + documentId + ")\n\n"
                + "(defrule document -> title sections)\n"
                + "(defrule title -> \"" + RuleFile.escape(title) + "\")\n";
        for (Section s : sections) {
            if (s.children().isEmpty()) continue;
            file = rules.addSection(file, s.name(), s.heading(), source, s.children(), s.body().toString());
        }
        return file;
    }

    /**
     * Returns a map of filenames to the text of code and formula blocks, where each key is the name assigned to the
     * block in the rule file.
     */
    public Map<String, String> codeFiles() {
        return Map.copyOf(codeFiles);
    }

    private void add(String block, String blockRules) {
        if (sections.isEmpty()) sections.add(new Section("s1", title, new ArrayList<>(), new StringBuilder()));
        Section s = sections.get(sections.size() - 1);
        s.children().add(block);
        s.body().append(blockRules);
    }

    private String neighbour(int i) {
        return i >= 0 && i < blocks.size() && blocks.get(i).kind() == BookText.Kind.PARAGRAPH ? blocks.get(i).text() : "";
    }

    private String newBlockName() {
        return "b" + (++blockCount);
    }
}
