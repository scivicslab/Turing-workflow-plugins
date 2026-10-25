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

    @Test
    void aChapterWithoutNumberedSectionsTakesItsBoldHeadingsAsSections() {
        var o = BookOutline.read(new java.util.TreeMap<>(java.util.Map.of(
                "Chap03/p1.md", "## **Chapter 3: Business Continuity Planning**\n\n## **THE TOPICS COVERED INCLUDE:**\n\n## **Planning for Business Continuity**\n\ntext\n\n"
                        + "## TIPS FOR SELECTING A TEAM\n\n## **Project Scope and Planning**\n\ntext\n",
                "Chap04/p1.md", "## **Chapter 4: Laws**\n\n## Categories of Laws\n\ntext\n")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("3 chapter Business Continuity Planning", "3.1 section Planning for Business Continuity",
                "3.2 section Project Scope and Planning", "4 chapter Laws", "4.1 section Categories of Laws"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList(), "a plain heading among bold ones is a sidebar");
    }

    @Test
    void aSectionOrSubsectionNumberMayHaveThreeDigitsAChapterTwo() {
        var o = BookOutline.read(new java.util.TreeMap<>(java.util.Map.of(
                "Chap02/p1.md", "# 2 java.util\n\n## 2.9 class Arrays\n\n### 2.9.99 method Arrays.fill(int[], int)\n\ntext\n\n"
                        + "### 2.9.214 method Arrays.toString(Object[])\n\ntext\n\n## 2.100 class Timer\n\ntext\n\n"
                        + "## 3.1415 is pi to four places\n\n## 100.5 percent of the budget\n\n## 2.9.1000 not a member\n")));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("2 chapter Chapter 2", "2.9 section class Arrays",
                "2.9.99 subsection method Arrays.fill(int[], int)", "2.9.214 subsection method Arrays.toString(Object[])", "2.100 section class Timer"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList(),
                "four digits after a dot, or three before the first, make no heading");
    }

    @Test
    void theChaptersAndAppendicesItsDirectoriesNameAreAddedWhereTheOutlineHasNone() {
        var files = new java.util.TreeMap<String, String>(java.util.Map.of(
                "29_第27章 Recovery from Errors/p301.md", "## 27\n\n## Recovery from Errors\n\ntext\n",
                "29_第27章 Recovery from Errors/p311.md", "more of 27\n\n## Answers to All the Exercises\n\n- 2.5. an answer\n",
                "30_付録A Answers to All the Exercises/p321.md", "## 12.16. an answer\n",
                "31_付録B Basic Control Sequences/p351.md", "## A Bold, Centered Title\n"));
        var read = new BookOutline.Outline(java.util.List.of(
                new BookOutline.Node("27", "chapter", "27 Recovery from Errors", null, "29_第27章 Recovery from Errors/p301.md"),
                new BookOutline.Node("27.1", "section", "Answers to All the Exercises", "27", "29_第27章 Recovery from Errors/p311.md")), java.util.List.of());
        var o = BookOutline.withDirectoryChapters(files, read);
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("27 chapter 27 Recovery from Errors @29_第27章 Recovery from Errors/p301.md",
                "A chapter Answers to All the Exercises @29_第27章 Recovery from Errors/p311.md",
                "B chapter Basic Control Sequences @31_付録B Basic Control Sequences/p351.md"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title() + " @" + n.firstFile()).toList(),
                "chapter 27 is in the outline already; appendix A begins on the page its heading is on, and the section of the same title goes");
        var r = BookSections.split(files, o.nodes());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("27|## 27\n\n## Recovery from Errors\n\ntext\n\nmore of 27", "A|- 2.5. an answer\n\n## 12.16. an answer", "B|## A Bold, Centered Title"),
                r.sections().stream().map(x -> x.number() + "|" + x.text()).toList());
        var one = new java.util.TreeMap<String, String>(java.util.Map.of("Chap01/p1.md", "## 1 One\n", "30_付録A Answers/p1.md", "## x\n"));
        org.junit.jupiter.api.Assertions.assertSame(read, BookOutline.withDirectoryChapters(one, read), "one such directory leaves the outline as it is");
    }
}
