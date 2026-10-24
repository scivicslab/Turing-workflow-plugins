package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure tests of the table of contents read from headings, and one round trip through the H2 store. */
class BookOutlineTest {

    private static Map<String, String> book() {
        Map<String, String> f = new LinkedHashMap<>();
        // the path order puts the appendix before the chapters
        f.put("Appendix/p001.md", """
                ## Appendix

                Java keywords.
                """);
        f.put("Chap04/p001.md", """
                ---
                title: "x"
                ---
                ## **Chapter 4 ▪ Objects and Classes**

                In this chapter.

                ## 4.1. Introduction to Object-Oriented Programming

                Text.

                ## 4.1.1. Classes

                A class is a template.
                """);
        f.put("Chap04/p006.md", """
                ## 4.1.1. Classes

                (the page file repeats the heading)

                ## 4.1.1.1. A Deeper Number

                Rounded into 4.1.1.

                ## **[Chapter 6 of Volume II](https://example.com/v2)**.

                A cross-reference the OCR made a heading.

                ## 1. *Always keep data private.*

                A numbered item, not a heading.

                ## 4.2. Using Predefined Classes
                """);
        return f;
    }

    @Test
    void chaptersSectionsAndSubsectionsComeFromTheNumbersAndNothingElse() {
        BookOutline.Outline o = BookOutline.read(book());
        assertEquals(List.of(
                new BookOutline.Node("4", "chapter", "Objects and Classes", null, "Chap04/p001.md"),
                new BookOutline.Node("4.1", "section", "Introduction to Object-Oriented Programming", "4", "Chap04/p001.md"),
                new BookOutline.Node("4.1.1", "subsection", "Classes", "4.1", "Chap04/p001.md"),
                new BookOutline.Node("4.2", "section", "Using Predefined Classes", "4", "Chap04/p006.md"),
                new BookOutline.Node("A", "chapter", "Appendix", null, "Appendix/p001.md")), o.nodes());
        assertTrue(o.notes().contains("1 heading(s) seen again were not added"), o.notes().toString());
    }

    @Test
    void theOutlineIsStoredAndReadBack(@TempDir Path dir) throws Exception {
        BookOutline.Outline o = BookOutline.read(book());
        try (BookStore s = new BookStore("jdbc:h2:" + dir.resolve("books"))) {
            s.saveOutline("core", "/b", o);
            s.saveOutline("core", "/b", o);           // saving again replaces, it does not add
            assertEquals(List.of("core"), s.books());
            assertEquals(o.nodes(), s.outline("core"));
        }
    }

    @Test
    void aBookWithoutChapterHeadingsIsReadByItsNumbers() {
        var o = BookOutline.read(new java.util.TreeMap<>(java.util.Map.of(
                "Chap02/p1.md", "## 2 COMPUTER SYSTEMS\n\ntext\n\n## *2.1 Processors*\n\ntext\n\n## 2.1.1 CPU Organization\n\ntext\n\n## 0 Thread safety issues\n\n# grid-cert-request\n\n## 9\n",
                "Chap24/p1.md", "#### **24.2.1 The FilesForSale Service**\n\ntext\n\n## *A.2 Installing the Toolkit*\n")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("2 chapter COMPUTER SYSTEMS", "2.1 section Processors", "2.1.1 subsection CPU Organization",
                "24 chapter Chapter 24", "24.2.1 subsection The FilesForSale Service", "A chapter Appendix A", "A.2 section Installing the Toolkit"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList());
    }

    @Test
    void aBookCutIntoOneDirectoryPerSectionIsReadByItsDirectories() {
        var o = BookOutline.read(new java.util.TreeMap<>(java.util.Map.of(
                "1. Introduction _ ULK_p001-003/a.md", "## Chapter 1. Introduction\n\ntext\n",
                "1.1. Linux Versus Other Kernels _ ULK_p001-005/b.md", "## Linux Versus Other Kernels\n\ntext\n",
                "1.1. Linux Versus Other Kernels _ ULK_p006-008/c.md", "text\n",
                "11.1. The Role of Signals _ ULK_p001-005/d.md", "## The Role of Signals\n")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("1 chapter Introduction", "1.1 section Linux Versus Other Kernels",
                "11.1 section The Role of Signals"), o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList());
    }

    @Test
    void aBookCutIntoOneDirectoryPerChapterTakesItsTitleLikeHeadingsAsSections() {
        var o = BookOutline.read(new java.util.TreeMap<>(java.util.Map.of(
                "Chap01/CHAPTER 1_ Introduction _ LKN_p001-005/a.md", "## CHAPTER 1\n\n## The Linux Network Stack\n\ntext\n\n## Helper methods:\n\n## char name[IFNAMSIZ]\n\n#define X 1\n",
                "Chap01/CHAPTER 1_ Introduction _ LKN_p006-010/b.md", "## The Linux Network Stack\n\n## The Socket Buffer\n",
                "Appendix_C/APPENDIX C_ Glossary _ LKN_p001-005/c.md", "## Glossary Terms\n")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("1 chapter Introduction", "1.1 section The Linux Network Stack",
                "1.2 section The Socket Buffer", "C chapter Glossary", "C.1 section Glossary Terms"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList());
    }
}
