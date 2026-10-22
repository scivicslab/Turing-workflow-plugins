package com.scivicslab.turingworkflow.plugins.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * The cases the plan runner's text steps were tested with, before those steps moved here: the comparison of a text
 * before and after a rewrite, the verdicts remembered, the front matter, and a list written as lines.
 */
class TextsCasesTest {

    private final Texts texts = new Texts();

    private static final String HEAD = """
            ---
            id: Tiny_260914_oo01
            title: 小さな見本
            ---
            """;
    private static final String BODY = """

            # 小さな見本

            本文です。
            """;

    // ---- compare ----

    @Test
    void proseThatWasRewrittenIsNotAComplaint() {
        String before = "# 見本\n\n`chat-01` は 28030 番で動きます。\n\n```bash\ncurl http://localhost:28030/\n```\n";
        String after = "# 見本\n\n`chat-01` が使うポートは 28030 番である。\n\n```bash\ncurl http://localhost:28030/\n```\n";
        assertEquals("ACCEPT", texts.compare(before, after));
    }

    @Test
    void aCodeBlockThatChangedIsReported() {
        String verdict = texts.compare("# 見本\n\n```yaml\n- actor: loader\n  method: loadJar\n```\n",
                "# 見本\n\n```yaml\n- actor: this\n  method: askWorker\n```\n");
        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("loadJar"), "says which block went missing: " + verdict);
    }

    @Test
    void aCodeBlockThatWasAddedIsReported() {
        String verdict = texts.compare("# 見本\n\n本文です。\n", "# 見本\n\n本文です。\n\n```bash\necho hello\n```\n");
        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("echo hello"), verdict);
    }

    /** OpenAlex became Open Alex in one run, and the judge did not notice. */
    @Test
    void anIdentifierThatWentMissingIsReported() {
        String verdict = texts.compare("# 見本\n\n`OpenAlex` に聞きます。\n", "# 見本\n\n`Open Alex` に聞きます。\n");
        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("OpenAlex"), verdict);
    }

    /** OpenAlex is a name whether or not it is in backquotes. */
    @Test
    void aNameInPlainProseThatWentMissingIsReported() {
        String verdict = texts.compare("結果は OpenAlex の書誌 5 件だった。\n", "結果は Open Alex の書誌 5 件だった。\n");
        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("OpenAlex"), verdict);
    }

    /** Ordinary words are not names: rewriting a sentence must not be reported. */
    @Test
    void ordinaryWordsAreNotTreatedAsNames() {
        assertEquals("ACCEPT", texts.compare("This is the text. It says something.\n", "The text says something else entirely.\n"));
    }

    /** 28030 became "2 8030" in another run: the number is gone, and a new one appeared. */
    @Test
    void aNumberThatChangedIsReported() {
        String verdict = texts.compare("ポートは 28030 とします。\n", "ポートは 2 8030 とします。\n");
        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("28030"), verdict);
    }

    @Test
    void aNumberThatAppearedFromNowhereIsReported() {
        String verdict = texts.compare("この節には数字がありません。\n", "この節には数字がありません。ポートは 28030 です。\n");
        assertTrue(verdict.startsWith("REVISE:"), verdict);
        assertTrue(verdict.contains("28030"), verdict);
    }

    /** A heading the section criterion renames is prose, not an identifier or a number. */
    @Test
    void renamingASectionIsNotAComplaint() {
        assertEquals("ACCEPT", texts.compare("## 前提条件\n\nポート 28030 で動いていること。\n",
                "## Problem Definition\n\n動いているかどうかが分からない。ポート 28030 で動いていること。\n"));
    }

    @Test
    void aMissingTextIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> texts.compare(null, "x"));
    }

    // ---- sameAsLast ----

    @Test
    void theFirstVerdictIsNeverTheSameAsTheLast() {
        assertFalse(texts.sameAsLast("REVISE: 軸が混ざっています", "m"), "nothing to compare with yet");
        assertTrue(texts.sameAsLast("REVISE: 軸が混ざっています", "m"), "and it is remembered for the next round");
    }

    @Test
    void aVerdictThatSaysSomethingNewLetsTheFixingGoOn() {
        texts.sameAsLast("REVISE: 軸が混ざっています", "m");
        assertFalse(texts.sameAsLast("REVISE: 主語がありません", "m"));
        assertTrue(texts.sameAsLast("REVISE: 主語がありません", "m"), "the newer one is what is remembered");
    }

    /** Whitespace a model adds or drops is not a new complaint. */
    @Test
    void theSameComplaintSpacedDifferentlyIsStillTheSame() {
        texts.sameAsLast("REVISE: 軸が混ざっています", "m");
        assertTrue(texts.sameAsLast("REVISE:  軸が混ざっています\n", "m"));
    }

    @Test
    void eachCriterionRemembersItsOwn() {
        texts.sameAsLast("REVISE: A", "1");
        texts.sameAsLast("REVISE: B", "2");
        assertTrue(texts.sameAsLast("REVISE: A", "1"), "criterion 1 has seen this one before");
        assertFalse(texts.sameAsLast("REVISE: A", "2"), "criterion 2 has not");
    }

    // ---- front matter ----

    private JSONObject split(String text) {
        return new JSONObject(texts.takeFrontMatter(text));
    }

    @Test
    void aDocumentWithFrontMatterIsSplitAtItsClosingLine() {
        JSONObject s = split(HEAD + BODY);
        assertEquals(HEAD, s.getString("head"));
        assertEquals(BODY, s.getString("body"), "what is sent on holds no front matter");
    }

    @Test
    void aDocumentWithoutFrontMatterIsLeftWhole() {
        JSONObject s = split(BODY);
        assertEquals("", s.getString("head"));
        assertEquals(BODY, s.getString("body"));
    }

    /** A line of dashes inside the text is not a closing line: only the block at the very top is. */
    @Test
    void aRuleLaterInTheTextIsNotMistakenForTheClosingLine() {
        String text = "# 見本\n\n本文です。\n\n---\n\n続きです。\n";
        assertEquals("", split(text).getString("head"));
        assertEquals(text, split(text).getString("body"));
    }

    @Test
    void whatWasTakenOffIsPutBackInFrontAndNothingTakenOffMeansNothingPutBack() {
        assertEquals(HEAD + BODY, texts.joinFrontMatter(HEAD, BODY));
        assertEquals(BODY, texts.joinFrontMatter("", BODY));
    }

    @Test
    void theTwoStepsTogetherLeaveADocumentAsItWas() {
        JSONObject s = split(HEAD + BODY);
        assertEquals(HEAD + BODY, texts.joinFrontMatter(s.getString("head"), s.getString("body")));
    }

    /** One blank line is put between the front matter and the text, and never a second. */
    @Test
    void oneBlankLineSitsBetweenTheFrontMatterAndTheText() {
        assertEquals(HEAD + "\n# 小さな見本\n\n本文です。\n", texts.joinFrontMatter(HEAD, "# 小さな見本\n\n本文です。\n"));
        assertEquals(HEAD + BODY, texts.joinFrontMatter(HEAD, BODY));
    }

    // ---- joinLines ----

    @Test
    void aListBecomesOneLineEach() {
        assertEquals("written: a.md\nwritten: b.md", texts.joinLines("[\"written: a.md\",\"written: b.md\"]"));
    }

    /** An empty section says so rather than leaving a heading with nothing under it; a missing value reads as "". */
    @Test
    void anEmptyListSaysThereIsNothing() {
        assertEquals("なし", texts.joinLines(""));
        assertEquals("なし", texts.joinLines("[]"));
    }

    @Test
    void aPieceOfTextIsPassedThroughAsItIs() {
        assertEquals("written: a.md", texts.joinLines("\"written: a.md\""));
    }
}
