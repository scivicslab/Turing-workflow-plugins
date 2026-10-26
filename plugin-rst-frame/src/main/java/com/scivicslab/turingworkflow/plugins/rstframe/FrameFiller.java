package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs the text-based transformations required to fill a rule file by finding blanks, expanding frame blanks into
 * lists of sub-frames, and writing sentences or code into specific rules.
 *
 * <ul>
 * <li>{@code next} returns the next {@link Blank} to be filled in the provided rule file text, or {@code null} if no
 * more blanks remain.</li>
 * <li>{@code expand} replaces a frame blank with a list of its copies, expanding the sub-frame for each item, and
 * returns the updated rule file text.</li>
 * <li>{@code include} replaces an include blank with a single copy of the specified child frame and returns the
 * updated rule file text.</li>
 * <li>{@code fill} writes a single sentence into a blank, replacing its instruction, sample, and frame attributes, and
 * returns the updated rule file text.</li>
 * <li>{@code fillList} writes a list of items into a list blank, creating a sequence of numbered item rules, and
 * returns the updated rule file text.</li>
 * <li>{@code fillWithFile} writes a code blank as a rule that points to a specific file, and returns the updated rule
 * file text.</li>
 * <li>{@code codePrompt} returns a string formatted as a prompt for a local LLM to write code or commands for a
 * specific {@link Blank}.</li>
 * <li>{@code listPrompt} returns a string formatted as a prompt for a local LLM to list items for a frame blank.</li>
 * <li>{@code itemsPrompt} returns a string formatted as a prompt for a local LLM to list items for a list blank.</li>
 * <li>{@code sentencePrompt} returns a string formatted as a prompt for a local LLM to write a sentence for a sentence
 * blank.</li>
 * <li>{@code parseItems} returns a list of item names extracted from a reply, removing bullets and numbering.</li>
 * <li>{@code parseNames} returns a list of names extracted from a reply, removing bullets, numbering, and trailing
 * full stops.</li>
 * <li>{@code codeOf} returns the text of a code reply with the triple-backtick fences removed.</li>
 * <li>{@code codeFileName} returns the filename a code blank's code is kept in, based on its name.</li>
 * </ul>
 *
 * <p>This class holds no state; the rule file text is passed into and returned from every operation.</p>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public final class FrameFiller {

    /**
     * Represents a single placeholder in a rule file that a writer must fill or expand.
     *
     * <p>The record contains:
     * <ul>
     * <li>{@code name}: the name of the rule</li>
     * <li>{@code instruction}: what to write, as the frame says it</li>
     * <li>{@code sample}: a model sentence from another document, or {@code ""}</li>
     * <li>{@code frame}: the sub-frame's file name for a frame blank, else {@code ""}</li>
     * <li>{@code kind}: the blank's {@code :kind}, e.g. {@code code-block}, or {@code ""}</li>
     * <li>{@code include}: the child frame's file name for an include blank, else {@code ""}</li>
     * </ul>
     *
     * <p>An instance may serve several workflows or threads at once.
     *
     * @param name the rule's name
     * @param instruction what to write, as the frame says it
     * @param sample a model sentence from another document, or {@code ""}
     * @param frame the sub-frame's file name for a frame blank, else {@code ""}
     * @param kind the blank's {@code :kind}, e.g. {@code code-block}, or {@code ""}
     * @param include the child frame's file name for an include blank, else {@code ""}
     */
    public record Blank(String name, String instruction, String sample, String frame, String kind, String include) {
        public Blank(String name, String instruction, String sample, String frame, String kind) {
            this(name, instruction, sample, frame, kind, "");
        }

        /** Returns true if this blank is a list of items to be explained using a sub-frame. */
        public boolean isFrame() {
            return !frame.isBlank();
        }

        /** Returns true if this blank is an include blank, which inserts exactly one copy of a child frame. */
        public boolean isInclude() {
            return !include.isBlank();
        }

        /** Returns whether this blank is filled with code, kept in a separate file, and shown as a code block. */
        public boolean isCode() {
            return "code-block".equals(kind);
        }

        /** Returns whether this blank is filled with the items of a bulleted or numbered list. */
        public boolean isList() {
            return "bullet".equals(kind) || "number".equals(kind);
        }
    }

    /**
     * Matches a rule name that is a leaf of the n-th copy of a sub-frame, formatted as {@code <parent>/<n>/<suffix>}.
     */
    private static final Pattern LEAF = Pattern.compile("^(.+)/(\\d+)/([^/]+)$");

    /**
     * Returns the first {@code Blank} in the rule file that contains an {@code :instruction}, or {@code null} if no
     * such rule exists.
     */
    public Blank next(String fileText) {
        for (RuleFile.Rule r : RuleFile.parse(fileText).rules()) {
            String instruction = r.attr(":instruction");
            if (instruction == null) continue;
            String sample = r.attr(":sample");
            String frame = r.attr(":frame");
            String kind = r.symbolAfter(":kind");
            String include = r.attr(":include");
            return new Blank(r.name(), instruction, sample == null ? "" : sample, frame == null ? "" : frame,
                    kind == null ? "" : kind, include == null ? "" : include);
        }
        return null;
    }

    /**
     * Replaces a frame blank with a list of its copies and adds the copies after it. If the blank has a {@code
     * :heading}, the copies are inserted as a list following the blank; if the blank lacks a {@code :heading}, the
     * copies replace the blank's position in the rule that names it, and the blank itself is replaced by the names of
     * the copies. In all copies, the placeholder {@code {item}} is replaced by the item's name.
     *
     * @param fileText the rule file as it stands
     * @param blankName the name of the frame blank to expand
     * @param frameText the sub-frame's file; its first rule becomes the root of each copy
     * @param names the items to be explained; one copy is created per item, numbered from 1
     * @return the rule file containing the expanded copies
     * @throws IllegalArgumentException if blankName is not a frame blank, the sub-frame has no rules, or names is
     * empty
     */
    public String expand(String fileText, String blankName, String frameText, List<String> names) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule blank = file.rule(blankName);
        if (blank == null || blank.attr(":frame") == null) {
            throw new IllegalArgumentException(blankName + " is not a blank with a :frame");
        }
        if (names.isEmpty()) throw new IllegalArgumentException("no items to expand " + blankName + " with");
        List<RuleFile.Rule> frameRules = RuleFile.parse(frameText).rules();
        if (frameRules.isEmpty()) throw new IllegalArgumentException("the sub-frame has no rule");
        String root = frameRules.get(0).name();

        java.util.Set<String> defined = namesOf(frameRules);
        List<String> copies = new ArrayList<>();
        List<String> copyRoots = new ArrayList<>();
        for (int n = 1; n <= names.size(); n++) {
            String copyRoot = blankName + "/" + n;
            copyRoots.add(copyRoot);
            String item = RuleFile.escape(names.get(n - 1));
            for (RuleFile.Rule r : frameRules) {
                copies.add(form(rename(r.name(), root, copyRoot, defined), renameSymbols(r.items(), root, copyRoot, defined)).replace("{item}", item));
            }
        }
        if (blank.attr(":heading") == null) {
            // No heading: the copies take the blank's place in the rule that names it, with no list of their own.
            for (RuleFile.Rule parent : file.rules()) {
                if (parent.items().stream().noneMatch(t -> t.kind() == RuleFile.Token.Kind.SYMBOL && t.raw().equals(blankName))) continue;
                StringBuilder body = new StringBuilder();
                for (RuleFile.Token t : parent.items()) {
                    if (body.length() > 0) body.append(' ');
                    body.append(t.kind() == RuleFile.Token.Kind.SYMBOL && t.raw().equals(blankName) ? String.join(" ", copyRoots) : t.raw());
                }
                file = file.replace(parent.name(), form(parent.name(), body.toString()));
            }
            return file.replace(blankName, String.join("\n", copies)).text();
        }
        String list = form(blankName, String.join(" ", copyRoots) + restOf(blank.items()));
        return file.replace(blankName, list).insertAfter(blankName, copies).text();
    }

    /**
     * Replaces an include blank with one copy of the child frame.
     *
     * The child's first rule takes the blank's name, keeping the blank's other attributes such as {@code :heading},
     * and the child's other rules follow it, renamed under the blank's name.
     *
     * @param fileText the rule file as it stands
     * @param blankName the name of the blank to be replaced
     * @param frameText the text of the child frame to be included
     * @return the rule file with the include blank replaced by the child frame's rules
     * @throws IllegalArgumentException if blankName is not an include blank, or if the child frame has no rule
     */
    public String include(String fileText, String blankName, String frameText) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule blank = file.rule(blankName);
        if (blank == null || blank.attr(":include") == null) {
            throw new IllegalArgumentException(blankName + " is not a blank with an :include");
        }
        List<RuleFile.Rule> frameRules = RuleFile.parse(frameText).rules();
        if (frameRules.isEmpty()) throw new IllegalArgumentException("the child frame has no rule");
        String root = frameRules.get(0).name();
        java.util.Set<String> defined = namesOf(frameRules);
        StringBuilder rootBody = new StringBuilder();
        for (RuleFile.Token t : renameSymbols(frameRules.get(0).items(), root, blankName, defined)) {
            if (rootBody.length() > 0) rootBody.append(' ');
            rootBody.append(t.raw());
        }
        String head = form(blankName, rootBody + restOf(blank.items()));
        List<String> rest = new ArrayList<>();
        for (RuleFile.Rule r : frameRules.subList(1, frameRules.size())) {
            rest.add(form(rename(r.name(), root, blankName, defined), renameSymbols(r.items(), root, blankName, defined)));
        }
        return file.replace(blankName, head).insertAfter(blankName, rest).text();
    }

    /**
     * Replaces a blank's instruction, sample, and frame with a single sentence.
     *
     * @param fileText the rule file as it stands
     * @param blankName the name of the rule to fill
     * @param sentence the text to write into the blank
     * @return the rule file with the blank replaced by the sentence, which is enclosed in quotation marks and ends
     * with a full stop
     *
     * @throws IllegalArgumentException if no rule is named blankName
     */
    public String fill(String fileText, String blankName, String sentence) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule rule = file.rule(blankName);
        if (rule == null) throw new IllegalArgumentException("no rule named " + blankName);
        String body = "\"" + RuleFile.escape(withFullStop(oneLine(sentence))) + "\"" + restOf(rule.items());
        return file.replace(blankName, form(blankName, body)).text();
    }

    /**
     * Replaces a list blank with a sequence of item rules {@code <blank>/1}, {@code <blank>/2}, ..., in place of its
     * {@code :instruction} and {@code :sample}, while keeping its {@code :kind}.
     *
     * @param fileText the rule file as it stands
     * @param blankName the name of the blank to be filled
     * @param items the list of items to be written, one per rule
     * @return the rule file with the blank replaced by the list of item rules and the items themselves written as
     * quoted strings in those rules
     *
     * @throws IllegalArgumentException if blankName does not exist in fileText, if the rule is not a blank, or if
     * items is empty
     */
    public String fillList(String fileText, String blankName, List<String> items) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule rule = file.rule(blankName);
        if (rule == null) throw new IllegalArgumentException("no rule named " + blankName);
        if (items.isEmpty()) throw new IllegalArgumentException("no items to fill " + blankName + " with");
        StringBuilder children = new StringBuilder();
        List<String> itemRules = new ArrayList<>();
        for (int n = 1; n <= items.size(); n++) {
            String name = blankName + "/" + n;
            if (children.length() > 0) children.append(' ');
            children.append(name);
            itemRules.add(form(name, "\"" + RuleFile.escape(oneLine(items.get(n - 1))) + "\""));
        }
        return file.replace(blankName, form(blankName, children + restOf(rule.items()))).insertAfter(blankName, itemRules).text();
    }

    /**
     * Replaces a code blank's {@code :instruction} and {@code :sample} with a rule that names the file containing the
     * code, while keeping the {@code :kind code-block} attribute.
     *
     * @param fileText the rule file as it stands
     * @param blankName the name of the rule to be replaced
     * @param fileName the name of the file where the code is kept
     * @return the rule file with the updated rule
     * @throws IllegalArgumentException if no rule is named blankName
     */
    public String fillWithFile(String fileText, String blankName, String fileName) {
        RuleFile file = RuleFile.parse(fileText);
        RuleFile.Rule rule = file.rule(blankName);
        if (rule == null) throw new IllegalArgumentException("no rule named " + blankName);
        String body = ":file \"" + RuleFile.escape(fileName) + "\"" + restOf(rule.items());
        return file.replace(blankName, form(blankName, body)).text();
    }

    /**
     * Returns the name of the text file where the code for a code blank is stored, such that {@code p-run/code}
     * becomes {@code p-run_code.txt}.
     *
     * @param blankName the name of the blank whose code file name is to be determined
     */
    public String codeFileName(String blankName) {
        return blankName.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
    }

    /** Returns the code from a reply, stripping any surrounding Markdown code fences. */
    public String codeOf(String reply) {
        List<String> lines = new ArrayList<>(List.of(reply.strip().split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(0).strip().startsWith("```")) lines.remove(0);
        if (!lines.isEmpty() && lines.get(lines.size() - 1).strip().equals("```")) lines.remove(lines.size() - 1);
        return String.join("\n", lines).strip();
    }

    /**
     * Returns a string that contains the subject material, any sentences already written in the same paragraph as the
     * blank, the blank's instruction, and a command to write only the code or command itself.
     *
     * @param fileText the rule file as it stands, for the sentences already written in the blank's paragraph
     * @param blank the blank whose instruction is used
     * @param subject the material being explained
     */
    public String codePrompt(String fileText, Blank blank, String subject) {
        StringBuilder sb = new StringBuilder("Below is the material that is being explained to a reader.\n\n")
                .append(fenced(subject)).append("\n");
        String paragraph = writtenInParagraph(fileText, blank.name());
        if (!paragraph.isEmpty()) {
            sb.append("\nSentences already written in this paragraph, in order:\n").append(paragraph);
        }
        sb.append("\nInstruction: ").append(blank.instruction()).append("\n");
        sb.append("\nWrite only the code or command itself: no ``` fence, no explanation, no sentence before or after it.");
        return sb.toString();
    }

    /**
     * Constructs a text prompt for a local LLM to identify the items that need to be explained in a frame blank.
     *
     * @param blank the frame blank whose instruction is used in the prompt
     * @param subject the material to be explained, which is wrapped in a code block
     * @param maxItems the maximum number of items the LLM should list
     * @param language the language in which the items must be written
     * @return a string containing the material, the instruction from the blank, and the constraints on the list format
     */
    public String listPrompt(Blank blank, String subject, int maxItems, String language) {
        return "Below is the material that will be explained to a reader.\n\n"
                + fenced(subject)
                + "\n\nThe document's author describes this part as: " + blank.instruction() + "\n\n"
                + "List the items a reader needs explained, one per line, each only the item's name, at most "
                + maxItems + " items. Write the names in " + language
                + ". No numbering, bullet marks, quotation marks or full stops. Write nothing else.";
    }

    /**
     * Returns a string that is a prompt for a local LLM to generate a list of items, one per line, based on the
     * provided subject and the blank's instruction.
     *
     * The prompt includes the subject in a fenced code block, any sentences already written in the same paragraph as
     * the blank, the blank's instruction, and a model list if the blank contains a {@code :sample}.
     *
     * @param fileText the rule file as it stands, used to include sentences already written in the blank's paragraph
     * @param blank the blank whose instruction and sample are used to build the prompt
     * @param subject the material to be explained, which is placed in a fenced code block
     * @param language the language in which the items must be written
     * @return a string containing the formatted prompt for the LLM
     */
    public String itemsPrompt(String fileText, Blank blank, String subject, String language) {
        StringBuilder sb = new StringBuilder("Below is the material that is being explained to a reader.\n\n")
                .append(fenced(subject)).append("\n");
        String paragraph = writtenInParagraph(fileText, blank.name());
        if (!paragraph.isEmpty()) {
            sb.append("\nSentences already written in this paragraph, in order:\n").append(paragraph);
        }
        sb.append("\nInstruction: ").append(blank.instruction()).append("\n");
        if (!blank.sample().isBlank()) {
            sb.append("Model list (from a different document, items separated by \" / \"; do not copy it, write this "
                    + "material's own items in the same role): ").append(blank.sample()).append("\n");
        }
        sb.append("\nWrite only the list's items, one per line, in ").append(language)
          .append(": no bullet marks, numbering, heading or sentence before or after them.");
        return sb.toString();
    }

    /**
     * Returns a list of items extracted from a reply, where each item is a single line with any leading bullets or
     * numbering removed.
     *
     * @param reply the text from the model to be parsed
     */
    public List<String> parseItems(String reply) {
        List<String> items = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String item = line.strip().replaceFirst("^(?:[-*・]|\\d+[.)）])\\s*", "").strip();
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    /**
     * Returns a prompt that asks a writer to produce a sentence or sentences for a specific blank.
     *
     * <p>If the {@code blank} is a leaf of a sub-frame copy (named {@code <parent>/<n>/<suffix>}), the prompt
     * specifies the n-th item from {@code names} and shows sentences already written for that specific item.
     * Otherwise, the prompt treats the {@code blank} as a general part of the material and shows sentences already
     * written in the same paragraph.</p>
     *
     * @param fileText the rule file as it stands
     * @param blank the blank to be filled
     * @param subject the material being explained
     * @param names the names of the items if the blank is a sub-frame copy
     * @param language the language in which the writer must respond
     * @return a string containing the instructions, the subject, any existing sentences, and a model sentence if
     * available
     */
    public String sentencePrompt(String fileText, Blank blank, String subject, List<String> names, String language) {
        return sentencePrompt(fileText, blank, subject, names, language, List.of());
    }

    /**
     * Generates a text prompt for a local LLM to write a sentence for a specific blank, including the subject
     * material, any sentences already written in the same paragraph, a model sentence, and examples of how other books
     * have filled similar roles.
     *
     * @param fileText the rule file as it stands, for the sentences already written in the blank's paragraph
     * @param blank the blank to be filled
     * @param subject the material being explained
     * @param names the names of the items if the blank is a leaf of a sub-frame copy
     * @param language the language the LLM must write in
     * @param examples a list of examples from other books showing how to fill the same part
     * @return a string containing the full prompt for the LLM
     */
    public String sentencePrompt(String fileText, Blank blank, String subject, List<String> names, String language,
                                 List<FrameExamples.Example> examples) {
        StringBuilder sb = new StringBuilder();
        Matcher leaf = LEAF.matcher(blank.name());
        if (leaf.matches()) {
            int n = Integer.parseInt(leaf.group(2));
            String item = n >= 1 && n <= names.size() ? names.get(n - 1) : "item " + n;
            sb.append("Below is the material, and a reader is being told about one item in it.\n\n")
              .append(fenced(subject)).append("\n\nThe item: \"").append(item).append("\"\n\n")
              .append("Sentences already written for this item:\n").append(written(fileText, leaf.group(1) + "/" + n + "/"));
        } else {
            sb.append("Below is the material that is about to be introduced to a reader.\n\n")
              .append(fenced(subject)).append("\n");
            String paragraph = writtenInParagraph(fileText, blank.name());
            if (!paragraph.isEmpty()) {
                sb.append("\nSentences already written in this paragraph, in order:\n").append(paragraph);
            }
        }
        sb.append("\nInstruction: ").append(blank.instruction()).append("\n");
        if (!blank.sample().isBlank()) {
            sb.append("Model sentence (from a different document; do not copy it, write this item's own content "
                    + "in the same role): ").append(blank.sample()).append("\n");
        }
        if (!examples.isEmpty()) {
            sb.append("\nParagraphs of other books that play the same part (each from a different document; do not copy "
                    + "them, take only how they serve the part):\n");
            for (FrameExamples.Example e : examples) sb.append("- [").append(e.source()).append("] ").append(e.text()).append('\n');
        }
        sb.append("\nWrite only the sentence(s) asked for: no preface, explanation, heading, quotation marks or bullet marks. "
                + "Write in ").append(language).append(", and end every sentence with the full stop of that language.");
        return sb.toString();
    }

    /**
     * Returns a list of item names extracted from a reply.
     *
     * The list contains the names from the reply, one per line, with any leading numbering, bullets, or whitespace
     * removed, and any trailing full stop removed.
     *
     * The number of names returned is at most maxItems.
     *
     * @param reply the text from the LLM containing the names
     * @param maxItems the maximum number of names to extract
     * @return a list of the extracted names
     */
    public List<String> parseNames(String reply, int maxItems) {
        List<String> names = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String name = line.strip().replaceFirst("^[-*・\\d.)）\\s]+", "").replaceFirst("[。.]$", "").strip();
            if (!name.isEmpty()) names.add(name);
            if (names.size() == maxItems) break;
        }
        return names;
    }

    /**
     * Returns a string of sentences that appear in the same rule as {@code blankName} but precede it, with each
     * sentence on a new line starting with a dash.
     *
     * @param fileText the rule file as it stands
     * @param blankName the name of the rule to find the preceding sentences for
     * @return a string of sentences from the same rule, or an empty string if no such sentences exist or if {@code
     * blankName} is not found
     */
    private String writtenInParagraph(String fileText, String blankName) {
        RuleFile file = RuleFile.parse(fileText);
        for (RuleFile.Rule parent : file.rules()) {
            boolean holds = parent.items().stream()
                    .anyMatch(t -> t.kind() == RuleFile.Token.Kind.SYMBOL && t.raw().equals(blankName));
            if (!holds) continue;
            StringBuilder sb = new StringBuilder();
            for (RuleFile.Token t : parent.items()) {
                if (t.kind() != RuleFile.Token.Kind.SYMBOL || t.raw().equals(blankName)) continue;
                RuleFile.Rule sibling = file.rule(t.raw());
                if (sibling != null && sibling.isSentence()) {
                    sb.append("- ").append(sibling.items().get(0).value()).append('\n');
                }
            }
            return sb.toString();
        }
        return "";
    }

    private String written(String fileText, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (RuleFile.Rule r : RuleFile.parse(fileText).rules()) {
            if (r.name().startsWith(prefix) && r.isSentence()) {
                sb.append("- ").append(r.name().substring(prefix.length())).append(": ")
                  .append(r.items().get(0).value()).append('\n');
            }
        }
        return sb.length() == 0 ? "(none yet)\n" : sb.toString();
    }

    private static String fenced(String subject) {
        return "```\n" + subject.stripTrailing() + "\n```";
    }

    private static String form(String name, String body) {
        return "(defrule " + name + " -> " + body + ")";
    }

    private static String form(String name, List<RuleFile.Token> items) {
        StringBuilder sb = new StringBuilder();
        for (RuleFile.Token t : items) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.raw());
        }
        return form(name, sb.toString());
    }

    private static List<RuleFile.Token> renameSymbols(List<RuleFile.Token> items, String root, String copyRoot, java.util.Set<String> defined) {
        List<RuleFile.Token> out = new ArrayList<>();
        for (RuleFile.Token t : items) {
            out.add(t.kind() == RuleFile.Token.Kind.SYMBOL
                    ? new RuleFile.Token(t.kind(), rename(t.raw(), root, copyRoot, defined)) : t);
        }
        return out;
    }

    /**
     * Returns a new rule name that is a descendant of {@code copyRoot} if the original name is the {@code root} or a
     * descendant of it, or a descendant of {@code copyRoot} if the name is one of the names defined within the frame.
     *
     * <p>If the name is not the {@code root}, a descendant of the {@code root}, or in the {@code defined} set, it
     * returns the name unchanged.
     */
    private static String rename(String name, String root, String copyRoot, java.util.Set<String> defined) {
        if (name.equals(root)) return copyRoot;
        if (name.startsWith(root + "/")) return copyRoot + name.substring(root.length());
        if (defined.contains(name)) return copyRoot + "/" + name;
        return name;
    }

    private static java.util.Set<String> namesOf(List<RuleFile.Rule> rules) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (RuleFile.Rule r : rules) out.add(r.name());
        return out;
    }

    /**
     * Returns a string containing the parts of a rule that remain after the {@code :instruction}, {@code :sample},
     * {@code :frame}, and {@code :include} attributes are removed, with each part preceded by a single space. If no
     * such parts exist, returns an empty string.
     */
    private static String restOf(List<RuleFile.Token> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            RuleFile.Token t = items.get(i);
            if (t.kind() == RuleFile.Token.Kind.KEYWORD
                    && (t.raw().equals(":instruction") || t.raw().equals(":sample") || t.raw().equals(":frame")
                        || t.raw().equals(":include"))) {
                i++;
                continue;
            }
            sb.append(' ').append(t.raw());
        }
        return sb.toString();
    }

    /**
     * Returns a single string by joining the lines of the input text.
     *
     * <p>A space is inserted between lines only if neither the last character of the preceding line nor the first
     * character of the current line is a CJK character. Empty or blank lines are discarded.</p>
     *
     * @param text the multi-line string to join
     */
    private static String oneLine(String text) {
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String l = line.strip();
            if (l.isEmpty()) continue;
            if (sb.length() > 0 && !isCjk(sb.charAt(sb.length() - 1)) && !isCjk(l.charAt(0))) sb.append(' ');
            sb.append(l);
        }
        return sb.toString();
    }

    /**
     * Ensures the sentence ends with a full stop, using {@code 。} if the last character is a CJK character and {@code
     * .} otherwise.
     *
     * @param sentence the text to be checked and potentially terminated
     * @return the sentence ending with a full stop, or the original sentence if it already ends with one or is empty
     */
    private static String withFullStop(String sentence) {
        if (sentence.isEmpty()) return sentence;
        char last = sentence.charAt(sentence.length() - 1);
        if ("。．.！？!?」』）)".indexOf(last) >= 0) return sentence;
        return sentence + (isCjk(last) ? "。" : ".");
    }

    private static boolean isCjk(char c) {
        Character.UnicodeScript s = Character.UnicodeScript.of(c);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || "、。，．「」（）".indexOf(c) >= 0;
    }
}
