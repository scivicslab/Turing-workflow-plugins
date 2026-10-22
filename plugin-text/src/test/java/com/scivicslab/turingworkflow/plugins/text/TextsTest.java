package com.scivicslab.turingworkflow.plugins.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/** Pure tests of {@link Texts}, and one round through its actor. */
class TextsTest {

    private final Texts texts = new Texts();

    @Test
    void aListIsWrittenOneLineEachAndNothingAsなし() {
        assertEquals("a\nb", texts.joinLines("[\"a\",\"b\"]"));
        assertEquals("なし", texts.joinLines("[]"));
        assertEquals("なし", texts.joinLines(""));
        assertEquals("one", texts.joinLines("\"one\""));
    }

    @Test
    void theSameVerdictTwiceIsNoticedWhateverItsSpacing() {
        assertFalse(texts.sameAsLast("REVISE: x  y", "m1"));
        assertTrue(texts.sameAsLast("REVISE: x\ny", "m1"));
        assertFalse(texts.sameAsLast("REVISE: x y", "m2"), "each memo is its own");
        assertFalse(texts.sameAsLast("REVISE: z", "m1"));
    }

    @Test
    void aRewriteThatLosesANameOrANumberIsSentBack() {
        assertEquals("ACCEPT", texts.compare("Port 8080 of `ChatSession`.", "ChatSession の `ChatSession` は 8080 番。"));
        String v = texts.compare("OpenAlex answers on 8080.", "Open Alex answers on 8081.");
        assertTrue(v.startsWith("REVISE:"), v);
        assertTrue(v.contains("消えた名前: OpenAlex"), v);
        assertTrue(v.contains("消えた数値: 8080"), v);
        assertTrue(v.contains("元の本文に無い数値: 8081"), v);
    }

    @Test
    void theFrontMatterComesOffAndGoesBack() {
        String doc = "---\nid: X\n---\n\nBody.\n---\nnot front matter\n";
        JSONObject split = new JSONObject(texts.takeFrontMatter(doc));
        assertEquals("---\nid: X\n---\n", split.getString("head"));
        assertEquals("\nBody.\n---\nnot front matter\n", split.getString("body"));
        assertEquals(doc, texts.joinFrontMatter(split.getString("head"), split.getString("body")));
        assertEquals("---\nid: X\n---\n\nBody.", texts.joinFrontMatter("---\nid: X\n---\n", "Body."));
        assertEquals("Body.", new JSONObject(texts.takeFrontMatter("Body.")).getString("body"));
    }

    @Test
    void theActorFailsSameAsLastForADifferentVerdict() {
        IIActorSystem system = new IIActorSystem("texts-test");
        try {
            TextsIIAR t = new TextsIIAR("texts", system);
            String args = new JSONObject().put("verdict", "REVISE: a").put("memo", "m").toString();
            ActionResult first = t.callByActionName("sameAsLast", args);
            ActionResult second = t.callByActionName("sameAsLast", args);
            assertFalse(first.isSuccess());
            assertTrue(second.isSuccess(), second.getResult());
        } finally {
            system.terminate();
        }
    }
}
