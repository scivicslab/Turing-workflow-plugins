package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure tests: a book's text cut at the nodes of its table of contents, across its page files; one round trip through H2. */
class BookSectionsTest {

    private static Map<String, String> book() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Chap04/p001.md", """
                ---
                title: "x"
                ---
                ## Chapter 4 ▪ Objects and Classes

                In this chapter.

                ## 4.1. Introduction

                ## 4.1.1. Classes

                A class is a template.
                """);
        f.put("Chap04/p006.md", """
                ---
                title: "x"
                ---
                It runs on into the next page file.

                ## 4.1.1. Classes

                The page file repeated the heading.

                ## 4.1.1.1. A Deeper Number

                Kept in 4.1.1.

                ## Chapter 6 of Volume II.

                A cross-reference the OCR made a heading.

                ## 4.2. Using Predefined Classes

                Text of 4.2.
                """);
        f.put("Preface/p001.md", """
                ## Preface

                Before any chapter.
                """);
        return f;
    }

    @Test
    void eachNodeGetsItsTextAcrossThePageFiles() {
        Map<String, String> f = book();
        BookSections.Result r = BookSections.split(f, BookOutline.read(f).nodes());
        Map<String, BookSections.Section> by = new LinkedHashMap<>();
        for (BookSections.Section s : r.sections()) by.put(s.number(), s);
        assertEquals(List.of("4", "4.1", "4.1.1", "4.2"), List.copyOf(by.keySet()));
        assertEquals("In this chapter.", by.get("4").text());
        assertEquals("", by.get("4.1").text(), "a section with no text before its first subsection");
        assertEquals("""
                A class is a template.

                It runs on into the next page file.

                The page file repeated the heading.

                ## 4.1.1.1. A Deeper Number

                Kept in 4.1.1.

                ## Chapter 6 of Volume II.

                A cross-reference the OCR made a heading.""", by.get("4.1.1").text());
        assertEquals(List.of("Chap04/p001.md", "Chap04/p006.md"), by.get("4.1.1").files());
        assertEquals("Text of 4.2.", by.get("4.2").text());
        assertEquals(List.of("2 non-blank line(s) in Preface come before any node and belong to none"), r.notes());
    }

    @Test
    void theSectionsAreStoredAndReadBack(@TempDir Path dir) throws Exception {
        Map<String, String> f = book();
        BookOutline.Outline o = BookOutline.read(f);
        BookSections.Result r = BookSections.split(f, o.nodes());
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("books"))) {
            s.saveOutline("core", "/b", o);
            s.saveSections("core", r.sections());
            s.saveSections("core", r.sections());
            assertEquals(r.sections(), s.sections("core"));
            assertEquals("/b", s.dirOf("core"));
        }
    }

    @Test
    void aParagraphCutByThePageIsJoinedAndAHyphenAtTheBreakIsDropped() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("Chap04/p001.md", "## Chapter 4 ▪ Objects\n\n## 4.1.1. Classes\n\nYou then supply new meth-\n");
        f.put("Chap04/p006.md", "---\ntitle: x\n---\nods and instance fields that apply,\n");
        f.put("Chap04/p011.md", "and nothing else.\n\n## 4.1.2. Objects\n\nAn object has state.\n");
        f.put("Chap04/p016.md", "```\ncode at the top of a page\n```\n");
        BookSections.Result r = BookSections.split(f, BookOutline.read(f).nodes());
        Map<String, String> by = new LinkedHashMap<>();
        for (BookSections.Section s : r.sections()) by.put(s.number(), s.text());
        assertEquals("You then supply new methods and instance fields that apply, and nothing else.", by.get("4.1.1"));
        assertEquals("An object has state.\n\n```\ncode at the top of a page\n```", by.get("4.1.2"), "code at a page's top is not joined");
    }

    @Test
    void aBookReadByItsNumbersIsCutWhereTheOutlineFoundEachNode() {
        var files = new java.util.TreeMap<String, String>(java.util.Map.of(
                "Chap02/p1.md", "## 2 COMPUTER SYSTEMS\n\nIntro text.\n\n## *2.1 Processors*\n\nProcessor text.\n",
                "Chap02/p2.md", "## 2.1 Processors (continued)\n\nMore processor text.\n\n## 2.2 Memory\n\nMemory text.\n"));
        var r = BookSections.split(files, BookOutline.read(files).nodes());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("2|Intro text.", "2.1|Processor text.\n\nMore processor text.", "2.2|Memory text."),
                r.sections().stream().map(x -> x.number() + "|" + x.text()).toList(), "a heading met again continues its node");
    }

    @Test
    void aSectionOverTheLimitIsCutAtItsHeadingsAndTheRestIsLeftAlone() {
        String big = "Intro.\n\n## First Part\n\n" + "a".repeat(30) + "\n\n```\n## not a heading in code\n```\n\n## Second Part\n\n" + "b".repeat(60)
                + "\n\n" + "c".repeat(60) + "\n\n" + "d".repeat(60) + "\n";
        var outline = java.util.List.of(new BookOutline.Node("3", "chapter", "Three", null, "c3/p1.md"),
                new BookOutline.Node("3.1", "section", "Child", "3", "c3/p2.md"), new BookOutline.Node("4", "chapter", "Four", null, "c4/p1.md"));
        var sections = java.util.List.of(new BookSections.Section("3", big, java.util.List.of("c3/p1.md", "c3/p2.md")),
                new BookSections.Section("3.1", "small", java.util.List.of("c3/p2.md")), new BookSections.Section("4", "small too", java.util.List.of("c4/p1.md")));
        var p = BookSections.splitLarge(outline, sections, 100);
        assertEquals(java.util.List.of("3 chapter Three", "3.p2 section Second Part", "3.p3 section Three (continued)", "3.1 section Child", "4 chapter Four"),
                p.outline().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList(),
                "a heading before half the limit and one in code cut nothing; past the limit with no heading, a blank line cuts");
        assertEquals(java.util.List.of("3", "3.p2", "3.p3", "3.1", "4"), p.sections().stream().map(BookSections.Section::number).toList());
        assertTrue(p.sections().get(0).text().contains("## not a heading in code"));
        assertEquals("b".repeat(60) + "\n\n" + "c".repeat(60), p.sections().get(1).text());
        assertEquals(1, p.notes().size());
        assertEquals(BookSections.LIMIT_FLOOR, BookSections.limit(sections), "a book of short sections is cut only past the floor");
    }

    @Test
    void cuttingAgainLeavesOutThePiecesOfTheRunBefore(@TempDir Path dir) throws Exception {
        Path book = dir.resolve("book");
        java.nio.file.Files.createDirectories(book.resolve("Chap02"));
        StringBuilder text = new StringBuilder("## 2 COMPUTER SYSTEMS\n\n");
        for (int i = 0; i < 40; i++) text.append("## topic ").append(i).append("\n\n").append("word ".repeat(600)).append("\n\n");
        java.nio.file.Files.writeString(book.resolve("Chap02/p1.md"), text.toString());
        for (String c : java.util.List.of("03", "04")) {
            java.nio.file.Files.createDirectories(book.resolve("Chap" + c));
            java.nio.file.Files.writeString(book.resolve("Chap" + c + "/p1.md"), "## " + Integer.parseInt(c) + " MEMORY AND DISKS\n\nA short chapter.\n");
        }
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "b", book.toString());
        new BookStructure().cutSections(db, "b");
        java.util.List<String> first, second;
        try (BookStore s = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            first = s.outline("b").stream().map(BookOutline.Node::number).toList();
        }
        new BookStructure().cutSections(db, "b");
        try (BookStore s = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            second = s.outline("b").stream().map(BookOutline.Node::number).toList();
            assertEquals(second.size(), s.sections("b").size());
        }
        assertTrue(first.size() > 2, first.toString());
        assertEquals("2", first.get(0));
        assertTrue(first.get(1).matches("2\\.p\\d+"), first.toString());
        assertEquals(first, second, "a second cut gives the same pieces, not pieces of pieces");
    }
}
