package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue of samples, read from the comment lines at the head of each sample
 * ({@code FramesAndSamplesFromBooks_261021_oo01}).
 */
@DisplayName("お手本の一覧: 先頭の行を読み、shape の近い順に並べ、also-seen を 1 行足す")
class SampleCatalogTest {

    private static final String A = """
            ;;; use: Shows a short program whole, then explains each concept it uses.
            ;;; source: /books/core/p001.md / 3.1. A Simple Java Program
            ;;; shape: nucleus → code → nucleus+definition → nucleus+elaboration-additional
            ;;; also-seen: /books/core/p011.md / 3.2. Comments
            (in-package :rst)
            """;
    private static final String B = """
            ;;; use: Gives a procedure, then derives its cost.
            ;;; source: /books/algo/p101.md / 2.4 2分探索木
            ;;; shape: nucleus → code → nucleus+evidence+conclusion
            (in-package :rst)
            """;

    @Test
    @DisplayName("先頭の行から、名前・用途・出典の数・shape を読む")
    void theHeadLinesAreRead() {
        List<SampleCatalog.Entry> entries = SampleCatalog.entries(Map.of("explain-program", A, "derive-cost", B));
        SampleCatalog.Entry a = entries.stream().filter(e -> e.name().equals("explain-program")).findFirst().orElseThrow();
        assertEquals("Shows a short program whole, then explains each concept it uses.", a.use());
        assertEquals(List.of("nucleus", "code", "nucleus+definition", "nucleus+elaboration-additional"), a.shape());
        assertEquals(2, a.sources(), "source and one also-seen");
    }

    @Test
    @DisplayName("shape の近い順に並べる")
    void nearestFirst() {
        List<SampleCatalog.Entry> entries = SampleCatalog.entries(Map.of("explain-program", A, "derive-cost", B));
        List<SampleCatalog.Entry> near = SampleCatalog.nearest(entries,
                List.of("nucleus", "code", "nucleus+definition", "nucleus"), 5);
        assertEquals("explain-program", near.get(0).name());
        assertEquals(2, near.size());
    }

    @Test
    @DisplayName("also-seen は先頭の行の最後に 1 行足し、規則は変えない")
    void anAlsoSeenLineGoesAfterTheHeadLines() {
        String out = SampleCatalog.withAlsoSeen(B, "/books/algo/p111.md / 2.5 平衡木");
        assertTrue(out.startsWith("""
                ;;; use: Gives a procedure, then derives its cost.
                ;;; source: /books/algo/p101.md / 2.4 2分探索木
                ;;; shape: nucleus → code → nucleus+evidence+conclusion
                ;;; also-seen: /books/algo/p111.md / 2.5 平衡木
                (in-package :rst)
                """), out);
    }
}
