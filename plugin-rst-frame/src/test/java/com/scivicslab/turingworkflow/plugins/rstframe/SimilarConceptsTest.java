package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Pure tests: the nearest units from other sections, the question on them, and the similar units a reply names. */
class SimilarConceptsTest {

    private static final SimilarConcepts.Unit INT = new SimilarConcepts.Unit("b", "3.3.1", 1, "int", "int. set of: 32-bit integer.");
    private static final SimilarConcepts.Unit LONG = new SimilarConcepts.Unit("b", "3.3.2", 1, "long", "long. set of: 64-bit integer.");
    private static final SimilarConcepts.Unit LOOP = new SimilarConcepts.Unit("b", "3.8.4", 1, "for loop", "for loop. sequence.");
    private static final SimilarConcepts.Unit SAME = new SimilarConcepts.Unit("b", "3.3.1", 2, "short", "short.");

    @Test
    void theNearestUnitsComeFromOtherSectionsClosestFirst() {
        Map<String, float[]> v = Map.of(INT.key(), new float[] {1, 0}, LONG.key(), new float[] {0.9f, 0.1f}, LOOP.key(), new float[] {0, 1},
                SAME.key(), new float[] {1, 0});
        assertEquals(List.of(LONG, LOOP), SimilarConcepts.nearest(INT, List.of(INT, LONG, LOOP, SAME), v, 8), "short is in int's own section");
        assertEquals(List.of(LONG), SimilarConcepts.nearest(INT, List.of(INT, LONG, LOOP, SAME), v, 1));
    }

    @Test
    void theReplyNamesTheCandidatesOfTheSameKindWithWhatTheyShare() {
        String q = SimilarConcepts.question(INT, List.of(LONG, LOOP));
        assertTrue(q.contains("c1: long. set of: 64-bit integer.\nc2: for loop. sequence.\n"), q);
        assertEquals(List.of(new SimilarConcepts.Similar(INT, LONG, "integer type")),
                SimilarConcepts.read("**c1** | integer type.\nc1 | twice\nc2 | none\nc7 | out of range\n", INT, List.of(LONG, LOOP)));
    }
}
