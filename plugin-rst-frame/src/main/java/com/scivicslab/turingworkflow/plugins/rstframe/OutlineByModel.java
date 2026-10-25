package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A book's table of contents read by the model, for a book whose headings no rule reads well (chapters headed in many
 * ways, sidebars and captions marked like sections, titles left as plain lines by the OCR). The candidate lines are the
 * book's markdown headings outside code, and, in a book with few headings, its short lines that could be titles; the
 * model is shown them in order, a chunk at a time, each with its file, and says which are chapters, sections or
 * subsections. The code numbers them in the order they come: the model decides the levels, not the numbers.
 */
public final class OutlineByModel {

    /** One line that may be a title: its file, its text without heading marks, and whether it was a markdown heading. */
    public record Candidate(String file, String text, boolean heading, int textBefore) {

        public Candidate(String file, String text, boolean heading) {
            this(file, text, heading, 0);
        }
    }

    /** How many candidates one question shows. */
    public static final int CHUNK = 150;
    /** A chapter's line that gives only its number: "27", "CHAPTER ONE", "Chapter 3", "第3章". */
    private static final Pattern ONLY_A_NUMBER = Pattern.compile("(?i)^(?:(?:chapter|part)\\s+)?(?:\\d{1,3}|[ivxlc]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\.?$|^第\\s*[0-9０-９一二三四五六七八九十百]+\\s*[章部]$");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*)$");
    private static final Pattern LEADERS = Pattern.compile("(?:[-.·…]\\s*){4,}\\s*\\d+\\s*$");
    private static final Pattern PLAIN_TITLE = Pattern.compile("^(?:第\\s*[0-9０-９一二三四五六七八九十百]+\\s*[章節部]|[0-9]{1,2}(?:\\.[0-9]{1,2})*\\.?\\s|[A-Z]).{1,80}$");

    private OutlineByModel() {
    }

    /** @return the lines of the book that may be titles, in reading order */
    /** @return the book's files in the book's order: front matter, then the chapters, then appendices, answers, glossary and index */
    public static Map<String, String> inBookOrder(Map<String, String> files) {
        List<String> keys = new ArrayList<>(files.keySet());
        java.util.function.ToIntFunction<String> part = k -> {
            String top = k.contains("/") ? k.substring(0, k.indexOf('/')) : k;
            if (top.matches("(?i)^(appendix|assessment|answer|glossary|index|bibliograph|references).*")) return 2;
            if (top.matches("(?i)^(chap|chapter|part)?\\s*_?\\d+.*")) return 1;
            return 0;
        };
        keys.sort(java.util.Comparator.comparingInt(part).thenComparing(java.util.Comparator.naturalOrder()));
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) out.put(k, files.get(k));
        return out;
    }

    public static List<Candidate> candidates(Map<String, String> files) {
        List<Candidate> headings = new ArrayList<>(), plain = new ArrayList<>();
        int text = 0;
        for (Map.Entry<String, String> f : files.entrySet()) {
            boolean code = false;
            for (String line : f.getValue().split("\n")) {
                if (line.startsWith("```")) code = !code;
                if (code || line.isBlank() || line.startsWith("---") || line.startsWith("title:") || line.startsWith("![")) {
                    if (code && !line.isBlank()) text++;
                    continue;
                }
                Matcher m = HEADING.matcher(line);
                // a line of the table of contents: its title, dot or dash leaders, and a page number
                if (LEADERS.matcher(line).find()) continue;
                if (m.matches()) {
                    String t = BookOutline.clean(m.group(1));
                    if (!t.isEmpty() && t.length() <= 120) {
                        headings.add(new Candidate(f.getKey(), t, true, text));
                        text = 0;
                    }
                } else {
                    String t = line.strip();
                    if (PLAIN_TITLE.matcher(t).matches() && !t.endsWith("。") && !t.endsWith(".") && !t.endsWith(",") && !t.endsWith("、")) {
                        plain.add(new Candidate(f.getKey(), BookOutline.clean(t), false, text));
                    }
                    text++;
                }
            }
        }
        // a book whose OCR kept its headings as headings is read by them; one with few headings by its plain lines too
        if (headings.size() >= files.size()) return headings;
        List<Candidate> all = new ArrayList<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            for (Candidate c : headings) if (c.file().equals(f.getKey())) all.add(c);
            for (Candidate c : plain) if (c.file().equals(f.getKey())) all.add(c);
        }
        return all;
    }

    /** @return the question for one chunk of candidates, numbered from {@code first} */
    public static String question(String book, List<Candidate> chunk, int first, String lastChapter) {
        StringBuilder sb = new StringBuilder();
        String file = null;
        for (int i = 0; i < chunk.size(); i++) {
            Candidate c = chunk.get(i);
            if (!c.file().equals(file)) {
                file = c.file();
                sb.append("[file ").append(file.length() > 90 ? "..." + file.substring(file.length() - 90) : file).append("]\n");
            }
            sb.append("L").append(first + i).append(" (after ").append(c.textBefore()).append(" lines of text): ").append(c.text()).append('\n');
        }
        return "Below are lines from the book \"" + book + "\", in reading order, that may be titles of its chapters and sections; "
                + "each group shows the file it comes from." + (lastChapter == null ? "" : " The chapter before these lines is \"" + lastChapter + "\".")
                + "\n\n" + sb + "\n"
                + "Say which lines are the titles of the book's own parts, at which level:\n"
                + "chapter     a chapter or an appendix (\"Chapter 3 Business Continuity Planning\", \"第2章 開発プロセスの概要\", \"APPENDIX A\").\n"
                + "section     a section of the chapter it is in.\n"
                + "subsection  a part of the section it is in.\n"
                + "name        the name of the chapter just above, when the chapter's own line is only its number (\"CHAPTER ONE\" then "
                + "\"Zeno, Eudoxus, Archimedes\"; \"Chapter 2\" then \"Securing Applications\").\n"
                + "Each line says how many lines of text come before it since the line above. A run of titles with no text between them "
                + "is a table of contents or a list, not the book's chapters: leave it out, and take the titles where the text of the "
                + "chapter follows them.\n"
                + "Leave out every other line: a box or sidebar in capitals, a note or tip, a figure or table caption, a running head or "
                + "page header repeated on many pages, a line of code, a line of the table of contents or index (often ending with a "
                + "page number), a list item, a sentence. When the same title comes again (a page header, \"(continued)\"), leave it out "
                + "after the first time.\n"
                + "Write one line per title: the line's name, \" | \", chapter, section, subsection or name. For a chapter, add the "
                + "chapter's own number when the book shows it near the title (\"Chapter 4\", \"4\", \"CHAPTER FOUR\"). For example\n"
                + "L12 | chapter 4\nL13 | name\nL14 | section\nL20 | subsection\n"
                + "Write \"none\" when none of these lines is a title. Write nothing else.";
    }

    /** @return the level the reply gives each candidate it names, by its number */
    public static Map<Integer, String> read(String reply) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*L(\\d+)\\W*\\|\\s*\\W*(chapter|section|subsection|name)\\b(?:\\s+(\\d{1,3}))?").matcher(reply);
        // a chapter keeps the number the book gives it, as "chapter 4"
        while (m.find()) out.putIfAbsent(Integer.parseInt(m.group(1)), m.group(2).toLowerCase(Locale.ROOT) + (m.group(3) == null ? "" : " " + m.group(3)));
        return out;
    }

    /**
     * Numbers the titles the model chose, in order: a chapter takes the next chapter number, a section the next number in
     * its chapter, a subsection the next in its section; a title met again in the same chapter is left out.
     */
    public static BookOutline.Outline outline(List<Candidate> candidates, Map<Integer, String> levels) {
        // the chapters, each with its titles, in the order read; put in the book's order when most of them have their number
        List<List<Integer>> blocks = new ArrayList<>();
        List<Integer> given = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String level = levels.get(i);
            if (level == null) continue;
            if (level.startsWith("chapter")) {
                blocks.add(new ArrayList<>());
                String[] w = level.split(" ");
                given.add(w.length > 1 ? Integer.parseInt(w[1]) : null);
            }
            if (!blocks.isEmpty()) blocks.get(blocks.size() - 1).add(i);
        }
        long numbered = given.stream().filter(java.util.Objects::nonNull).count();
        if (numbered * 2 > given.size()) {
            List<Integer> order = new ArrayList<>();
            for (int b = 0; b < blocks.size(); b++) order.add(b);
            // a chapter without a number keeps its place behind the numbered one before it
            List<Integer> key = new ArrayList<>();
            int last = 0;
            for (Integer g : given) {
                if (g != null) last = g;
                key.add(last * 1000 + key.size());
            }
            order.sort(java.util.Comparator.comparingInt(key::get));
            Map<Integer, String> reordered = new LinkedHashMap<>();
            List<Candidate> cands = new ArrayList<>();
            for (int b : order) {
                for (int i : blocks.get(b)) {
                    reordered.put(cands.size(), levels.get(i));
                    cands.add(candidates.get(i));
                }
            }
            return number(cands, reordered);
        }
        return number(candidates, levels);
    }

    private static BookOutline.Outline number(List<Candidate> candidates, Map<Integer, String> levels) {
        List<BookOutline.Node> nodes = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        int chapter = 0, section = 0, subsection = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            String level = levels.get(i);
            if (level == null) continue;
            Candidate c = candidates.get(i);
            String key = chapter + "\t" + c.text().toLowerCase(Locale.ROOT);
            // the name of a chapter whose own line is only its number joins its title; a chapter that has its name already
            // takes the line as a section, so a title the model took for a name ("Answers to All the Exercises" after
            // "27 Recovery from Errors") still starts a node
            if (level.equals("name")) {
                int last = nodes.size() - 1;
                if (last >= 0 && nodes.get(last).level().equals("chapter") && ONLY_A_NUMBER.matcher(nodes.get(last).title()).matches()) {
                    BookOutline.Node ch = nodes.get(last);
                    nodes.set(last, new BookOutline.Node(ch.number(), ch.level(), ch.title() + " " + c.text(), ch.parent(), ch.firstFile()));
                    continue;
                }
                if (chapter == 0) continue;
                level = "section";
            }
            if (level.startsWith("chapter")) {
                chapter++;
                section = 0;
                subsection = 0;
                seen.clear();
                nodes.add(new BookOutline.Node(Integer.toString(chapter), "chapter", c.text(), null, c.file()));
                continue;
            }
            if (chapter == 0) {
                notes.add("\"" + c.text() + "\" comes before any chapter and was left out");
                continue;
            }
            if (!seen.add(key)) continue;
            if (level.equals("section") || section == 0) {
                section++;
                subsection = 0;
                nodes.add(new BookOutline.Node(chapter + "." + section, "section", c.text(), Integer.toString(chapter), c.file()));
            } else {
                subsection++;
                nodes.add(new BookOutline.Node(chapter + "." + section + "." + subsection, "subsection", c.text(), chapter + "." + section, c.file()));
            }
        }
        return new BookOutline.Outline(nodes, notes);
    }

    /** Reads the book's outline with the model, one chunk at a time in order. */
    public static BookOutline.Outline read(String book, Map<String, String> files, Function<String, String> writer, Consumer<String> log) {
        List<Candidate> cands = candidates(inBookOrder(files));
        Map<Integer, String> levels = new LinkedHashMap<>();
        String lastChapter = null;
        log.accept(cands.size() + " candidate line(s) in " + files.size() + " file(s), " + ((cands.size() + CHUNK - 1) / CHUNK) + " question(s)");
        for (int from = 0; from < cands.size(); from += CHUNK) {
            List<Candidate> chunk = cands.subList(from, Math.min(cands.size(), from + CHUNK));
            Map<Integer, String> got = Map.of();
            for (int attempt = 0; attempt < 2 && got.isEmpty(); attempt++) {
                String reply = writer.apply(question(book, chunk, from, lastChapter));
                got = read(reply);
                if (got.isEmpty() && reply.toLowerCase(Locale.ROOT).contains("none")) break;
            }
            for (Map.Entry<Integer, String> e : got.entrySet()) {
                if (e.getKey() < from || e.getKey() >= from + chunk.size()) continue;
                levels.put(e.getKey(), e.getValue());
                if (e.getValue().startsWith("chapter")) lastChapter = cands.get(e.getKey()).text();
            }
            log.accept("lines " + from + "-" + (from + chunk.size() - 1) + ": " + got.size() + " title(s)");
        }
        return outline(cands, levels);
    }
}
