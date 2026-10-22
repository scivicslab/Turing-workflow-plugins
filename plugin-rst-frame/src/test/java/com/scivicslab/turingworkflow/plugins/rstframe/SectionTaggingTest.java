package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each section made into a rule file by a writer's answers, stored in H2; a section already done is not asked again. */
class SectionTaggingTest {

    private static String fair(String q) {
        if (q.contains("misread")) return q.substring(q.indexOf("The paragraph:\n") + 15, q.indexOf("\n\nParagraph after:"));
        long n = q.lines().filter(l -> l.matches("^\\d+\\. .*")).count();
        StringBuilder sb = new StringBuilder("1: nucleus\n");
        for (int i = 2; i <= n; i++) sb.append(i).append(": elaboration-additional\n");
        return sb.toString();
    }

    @Test
    void everySectionGetsARuleFileWithItsOwnTitleAndCode(@TempDir Path dir) throws Exception {
        Path b = dir.resolve("book/Chap04/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 4 ▪ Objects and Classes\n\nIn this chapter.\n\n## 4.1.1. Classes\n\n"
                + "A class is a template. Objects are made from it.\n\n```\nclass A {}\n```\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        List<String> asked = new ArrayList<>();
        try (BookStore store = new BookStore("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            String summary = SectionTagging.tagAll(store, "core", List.of(), q -> { asked.add(q); return fair(q); }, 2, line -> { });
            assertEquals("2 section(s) of core made into rule files, 0 failed", summary);
            BookStore.SectionRules r = store.sectionRules("core", "4.1.1");
            assertTrue(r.rules().contains("(defrule title -> \"4.1.1. Classes\")"), r.rules());
            assertTrue(r.rules().contains("(defrule b1 -> :nucleus b1/1 :elaboration-additional b1/2)"), r.rules());
            assertTrue(r.rules().contains("(defrule b2 -> :file \"b2.txt\" :kind code-block)"), r.rules());
            assertEquals(Map.of("b2.txt", "class A {}"), r.code());
            assertEquals(2, r.questions());
            int before = asked.size();
            assertEquals("0 section(s) of core made into rule files, 0 failed",
                    SectionTagging.tagAll(store, "core", List.of(), q -> { asked.add(q); return fair(q); }, 2, line -> { }));
            assertEquals(before, asked.size(), "a section that has its rule file is not asked again");
        }
    }
}
