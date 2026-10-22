package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one whole markdown file of a book into one rst-on-lisp rule file, written beside it
 * ({@code FrameMakingWorkflow_261022_oo01}, step 1). The sentences are the book's own; a writer is asked only what
 * {@link BookRules} asks: each paragraph's OCR misreadings, then how its sentences relate to its nucleus.
 *
 * <p>Each heading of the file starts a section; blocks before the first heading go into a section under the file's
 * title. Code and formulas need no question and are kept as files beside the rule file ({@link #codeFiles()}); a list
 * is kept as a bullet rule. A relation answer that cannot be read is asked once more; after that the first sentence is
 * taken as the nucleus and a comment says so.</p>
 */
public final class BookFileToRules implements Questioner {

    private enum Phase { OCR, RELATIONS, RELATIONS_AGAIN }

    /** One section of the file: its rule name, its heading, its blocks by rule name, and their rules. */
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
     * @param file       the markdown file's text
     * @param source     the file's path, written into each section's {@code :source}
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

    /** @return how many paragraphs the file has: each is asked about twice */
    public int paragraphs() {
        return (int) blocks.stream().filter(b -> b.kind() == BookText.Kind.PARAGRAPH).count();
    }

    /** @return how many questions have been asked so far */
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

    /** @return the rule file; complete once {@link #nextQuestion()} has returned {@code null} */
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

    /** @return the code and formula blocks, by the file name their rules give, to be written beside the rule file */
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
