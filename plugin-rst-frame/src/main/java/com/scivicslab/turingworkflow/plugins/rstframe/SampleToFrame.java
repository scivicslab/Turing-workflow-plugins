package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a frame from a sample by asking a writer one question per block.
 *
 * <p>The frame preserves the sample's structure, where every sentence becomes a blank with a {@code :instruction}
 * describing the sentence's function and a {@code :sample} containing the original text. Code blocks become code
 * blanks and lists become list blanks.
 * <ul><li>{@link #nextQuestion()} returns a string that is a question for the writer, or {@code null} if every block
 * has been processed.</li><li>{@link #answer(String)} takes the writer's reply to the current question and stores the
 * resulting instructions.</li><li>{@link #frameFile()} returns the text of the sample's rule file, updated with the
 * collected instructions and renamed to a frame.</li><li>{@link #name()} returns the name of the sample.</li></ul>
 *
 * <p>One instance serves one sample and is used to drive a single sequence of questions and answers.
 *
 * <p>An error is thrown if {@link #answer(String)} is called when no question is currently open.
 *
 * <pre>{@code
 * SampleToFrame s = new SampleToFrame(sampleFile, "my-frame", codeFiles);
 * while (s.nextQuestion() != null) {
 *   String q = s.nextQuestion();
 *   // ... get reply ...
 *   s.answer(reply);
 * }
 * String frameText = s.frameFile();
 * }</pre>
 */
public final class SampleToFrame implements Questioner {

    private record Block(String name, String kind, List<String> leaves, List<String> relations, List<String> texts) {}

    private static final Pattern LINE = Pattern.compile("^\\s*(\\d+)\\s*[:.)]\\s*(.+?)\\s*$");

    private final String sample;
    private final String name;
    private final Map<String, String> codeFiles;
    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, String> instructions = new LinkedHashMap<>();   // rule name -> instruction
    private int next;
    private String question;
    private Block asking;

    /**
     * Initializes a frame from a sample's rule file, a name, and a map of code files, preparing a sequence of
     * questions for a writer.
     *
     * @param sample the sample's rule file
     * @param name the sample's name; the frame shares it
     * @param codeFiles the sample's code files, by the file name its rules give
     * @throws IllegalArgumentException if the sample has no section
     */
    public SampleToFrame(String sample, String name, Map<String, String> codeFiles) {
        this.sample = sample;
        this.name = name;
        this.codeFiles = Map.copyOf(codeFiles);
        RuleFile rf = RuleFile.parse(sample);
        RuleFile.Rule section = rf.rules().stream().filter(r -> r.attr(":heading") != null).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the sample has no section"));
        for (RuleFile.Token t : section.items()) {
            if (t.kind() == RuleFile.Token.Kind.KEYWORD) break;
            RuleFile.Rule r = rf.rule(t.raw());
            if (r == null) continue;
            String kind = r.symbolAfter(":kind");
            List<String> leaves = new ArrayList<>();
            List<String> relations = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            if ("code-block".equals(kind)) {
                texts.add(this.codeFiles.getOrDefault(r.attr(":file"), ""));
                blocks.add(new Block(r.name(), "code", leaves, relations, texts));
            } else if ("bullet".equals(kind) || "number".equals(kind)) {
                relations.add(kind);   // a list's one "relation" is its kind, kept on the blank
                for (RuleFile.Token item : r.items()) {
                    if (item.kind() != RuleFile.Token.Kind.SYMBOL || rf.rule(item.raw()) == null) continue;
                    texts.add(rf.rule(item.raw()).items().get(0).value());
                }
                blocks.add(new Block(r.name(), "list", leaves, relations, texts));
            } else {
                List<RuleFile.Token> items = r.items();
                for (int i = 0; i + 1 < items.size(); i++) {
                    if (items.get(i).kind() != RuleFile.Token.Kind.KEYWORD) continue;
                    RuleFile.Rule leaf = rf.rule(items.get(i + 1).raw());
                    if (leaf == null || !leaf.isSentence()) continue;
                    relations.add(items.get(i).raw().substring(1));
                    leaves.add(leaf.name());
                    texts.add(leaf.items().get(0).value());
                }
                blocks.add(new Block(r.name(), "paragraph", leaves, relations, texts));
            }
        }
    }

    /**
     * Returns a text instruction that asks a writer to describe a specific block (a paragraph, a list, or a code
     * block) from the sample, or {@code null} if all blocks have been processed.
     *
     * <p>The instruction is tailored to the block's type:
     * <ul><li>For a paragraph, it asks for a one-line instruction for each sentence, specifying what the sentence does
     * in the context of the paragraph.</li><li>For a list, it asks for a one-line instruction describing what the list
     * does in the section.</li><li>For a code block, it asks for a one-line instruction describing what the code block
     * does in the section.</li></ul>
     *
     * <p>A second call returns the same instruction until {@link #answer(String)} is called.
     *
     * @return the instruction text for the next block, or {@code null} if no more blocks remain to be asked about.
     */
    public String nextQuestion() {
        if (question != null) return question;
        if (next >= blocks.size()) return null;
        asking = blocks.get(next++);
        question = switch (asking.kind()) {
            case "code" -> "Below is a code block from a section of a book, after the text that leads to it.\n\n"
                    + leadingText() + "\n```\n" + asking.texts().get(0) + "\n```\n\n"
                    + "In one line, say what this code block does in the section, in words that do not name the book's "
                    + "subject, so the line also tells a writer what code to show when explaining something else. Write in English, "
                    + "starting with a verb, e.g. \"Show the whole program the section explains.\" Write that line and nothing else.";
            case "list" -> "Below is a list from a section of a book.\n\n- " + String.join("\n- ", asking.texts()) + "\n\n"
                    + "In one line, say what this list does in the section, in words that do not name the book's subject, starting "
                    + "with a verb, e.g. \"List the steps to run the program with an older version.\" Write that line and nothing else.";
            default -> {
                StringBuilder sb = new StringBuilder("Below are the sentences of one paragraph of a book, numbered, each with its "
                        + "relation to the paragraph's nucleus.\n\n");
                for (int i = 0; i < asking.texts().size(); i++) {
                    sb.append(i + 1).append(". [").append(asking.relations().get(i)).append("] ").append(asking.texts().get(i)).append('\n');
                }
                sb.append("\nFor each sentence, write an instruction that tells a writer what sentence to write in this place when "
                        + "explaining some other material: what the sentence does, not what it says. Do not name the book's subject "
                        + "(no language, no feature, no data structure). Write in English, one line per sentence: its number, a "
                        + "colon, and the instruction, e.g. \"2: Define the item in plain words.\" Write nothing else.");
                yield sb.toString();
            }
        };
        return question;
    }

    /**
     * Returns the text of the block immediately preceding the one currently being asked about, or an empty string if
     * no such block exists or if the preceding block is not a paragraph.
     */
    private String leadingText() {
        int i = next - 2;
        return i >= 0 && blocks.get(i).kind().equals("paragraph") ? String.join(" ", blocks.get(i).texts()) + "\n" : "";
    }

    /**
     * Records the writer's reply to the question produced by {@link #nextQuestion()}.
     *
     * <p>If the reply is null, the instructions for the current block are set to default values that describe the role
     * of the sentences or elements relative to the block's nucleus or structure. If the reply is not null, it is
     * parsed to extract instructions for each sentence in a paragraph or a single instruction for a code block or
     * list, which are then stored.
     *
     * <p>An action result is a success if the reply is processed without error; if the reply is null when no question
     * is open, an {@link IllegalStateException} is thrown.
     *
     * @param reply the text provided by the writer, or {@code null} to use default instructions for the current block
     */
    public void answer(String reply) {
        if (question == null) throw new IllegalStateException("no question is open");
        String r = reply == null ? "" : reply.strip();
        if (asking.kind().equals("paragraph")) {
            Map<Integer, String> byNumber = new LinkedHashMap<>();
            for (String line : r.split("\\R")) {
                Matcher m = LINE.matcher(line);
                if (m.matches()) byNumber.put(Integer.parseInt(m.group(1)), m.group(2));
            }
            for (int i = 0; i < asking.leaves().size(); i++) {
                String given = byNumber.get(i + 1);
                String relation = asking.relations().get(i);
                instructions.put(asking.leaves().get(i), given != null ? given
                        : relation.equals("nucleus") ? "Write the sentence the paragraph is there to say."
                        : "Write the sentence that stands to the paragraph's nucleus as its " + relation + ".");
            }
        } else {
            String line = r.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
            instructions.put(asking.name(), line.isEmpty()
                    ? (asking.kind().equals("code") ? "Show the code the paragraph before leads to." : "List the items the paragraph before leads to.")
                    : line);
        }
        question = null;
    }

    /** Returns the name of the sample, which is also the name of the frame produced by this object. */
    public String name() {
        return name;
    }

    /**
     * Returns a string containing the rule file that defines the frame, with the instructions and samples updated from
     * the writer's replies.
     *
     * <p>The returned string is complete once {@link #nextQuestion()} has returned {@code null}.
     */
    public String frameFile() {
        RuleFile rf = RuleFile.parse(sample);
        for (Block b : blocks) {
            if (b.kind().equals("paragraph")) {
                for (int i = 0; i < b.leaves().size(); i++) {
                    String leaf = b.leaves().get(i);
                    rf = rf.replace(leaf, "(defrule " + leaf + " -> :instruction \"" + RuleFile.escape(instructions.getOrDefault(leaf, ""))
                            + "\" :sample \"" + RuleFile.escape(b.texts().get(i)) + "\")");
                }
            } else if (b.kind().equals("code")) {
                rf = rf.replace(b.name(), "(defrule " + b.name() + " -> :instruction \""
                        + RuleFile.escape(instructions.getOrDefault(b.name(), "")) + "\" :kind code-block)");
            } else {
                rf = rf.replace(b.name(), "(defrule " + b.name() + " -> :instruction \""
                        + RuleFile.escape(instructions.getOrDefault(b.name(), "")) + "\" :sample \""
                        + RuleFile.escape(String.join(" / ", b.texts())) + "\" :kind " + b.relations().get(0) + ")");
            }
        }
        String text = rf.text();
        // the list's item rules are no longer reached; drop them so the file holds only what the frame uses
        for (Block b : blocks) {
            if (!b.kind().equals("list")) continue;
            text = text.replaceAll("(?m)^\\(defrule " + Pattern.quote(b.name()) + "/\\d+ -> \"(?:[^\"\\\\]++|\\\\.)*+\"\\)\\R", "");
        }
        StringBuilder head = new StringBuilder();
        StringBuilder rest = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (rest.length() == 0 && line.startsWith(";;; ")) {
                if (!line.startsWith(";;; also-seen:")) head.append(line).append('\n');
            } else {
                rest.append(line).append('\n');
            }
        }
        head.append(";;; made-from: ").append(name).append('\n');
        return head + rest.toString().replaceFirst("Sample\\)", "Frame)").stripTrailing() + "\n";
    }
}
