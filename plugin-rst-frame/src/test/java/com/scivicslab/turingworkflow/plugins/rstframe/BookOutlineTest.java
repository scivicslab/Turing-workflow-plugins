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
        f.put("Appendix/p001.md", """
                ## Appendix

                Java keywords.
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
}
