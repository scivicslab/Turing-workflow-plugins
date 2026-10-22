package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
