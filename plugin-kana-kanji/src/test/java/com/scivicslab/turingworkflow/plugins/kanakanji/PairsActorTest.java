package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.core.ActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PairsActorTest {

    static class StubPairsActor extends PairsActor {
        StubPairsActor() {
            super("test-pairs", null);
        }
    }

    private StubPairsActor actor;

    @BeforeEach
    void setUp() {
        actor = new StubPairsActor();
    }

    // ---- checkHiragana ----

    @Test
    void checkHiragana_validPairs_succeeds() {
        // hiragana TAB kanji format
        String response = "てんき\t天気\nかぜ\t風\nはな\t花";
        ActionResult result = actor.checkHiragana(response);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("3");
    }

    @Test
    void checkHiragana_noHiraganaInAllJapaneseLines_fails() {
        // kanji without hiragana readings — LLM failed
        String response = "WEATHER\t天気\nWIND\t風";
        ActionResult result = actor.checkHiragana(response);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("hiragana");
    }

    @Test
    void checkHiragana_asciiOnlyContent_succeeds() {
        // No Japanese characters in kanji column — no reading required
        String response = "ASCII\tHello World\nNUMBER\t12345";
        ActionResult result = actor.checkHiragana(response);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void checkHiragana_emptyInput_fails() {
        ActionResult result = actor.checkHiragana("");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void checkHiragana_noTabLines_fails() {
        ActionResult result = actor.checkHiragana("no tab here\nalso no tab");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("No tab-separated lines");
    }

    @Test
    void checkHiragana_mixedJapaneseAndAscii_passesWithPartialHiragana() {
        // One Japanese line has hiragana, one ASCII line doesn't need it
        String response = "てんき\t天気\nASCII\tHello";
        ActionResult result = actor.checkHiragana(response);
        assertThat(result.isSuccess()).isTrue();
    }

    // ---- setPageInfo ----

    @Test
    void setPageInfo_tabSeparated_extractsPageAndSource() {
        ActionResult result = actor.setPageInfo("42\tpage042.tsv");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains("page=42").contains("source=page042.tsv");
    }

    @Test
    void setPageInfo_noTab_setsPageOnly() {
        ActionResult result = actor.setPageInfo("99");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains("page=99");
    }

    @Test
    void setPageInfo_empty_fails() {
        ActionResult result = actor.setPageInfo("");
        assertThat(result.isSuccess()).isFalse();
    }

    // ---- openOutput / writePairs / closeOutput ----

    @Test
    void writePairs_withoutOpenOutput_fails() {
        ActionResult result = actor.writePairs("てんき\t天気");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("openOutput");
    }

    @Test
    void openOutput_writePairs_closeOutput_roundtrip(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("output.tsv");

        ActionResult open = actor.openOutput(outputFile.toString());
        assertThat(open.isSuccess()).isTrue();

        actor.setPageInfo("1\ttest.tsv");
        ActionResult write = actor.writePairs("てんき\t天気\nかぜ\t風");
        assertThat(write.isSuccess()).isTrue();
        assertThat(write.getResult()).isEqualTo("2");

        ActionResult close = actor.closeOutput("");
        assertThat(close.isSuccess()).isTrue();
        assertThat(close.getResult()).contains("2");

        // Verify file contents
        String content = Files.readString(outputFile);
        assertThat(content).startsWith("hiragana\tkanji\tpage\tsource\n");
        assertThat(content).contains("てんき\t天気\t1\ttest.tsv");
        assertThat(content).contains("かぜ\t風\t1\ttest.tsv");
    }

    @Test
    void writePairs_skipsLinesWithoutTab(@TempDir Path tempDir) throws IOException {
        Path outputFile = tempDir.resolve("output.tsv");
        actor.openOutput(outputFile.toString());
        actor.setPageInfo("1\ttest.tsv");

        ActionResult write = actor.writePairs("no tab here\nてんき\t天気\nalso no tab");
        assertThat(write.isSuccess()).isTrue();
        assertThat(write.getResult()).isEqualTo("1"); // only the tab line is written

        actor.closeOutput("");
    }

    @Test
    void closeOutput_alreadyClosed_succeeds() {
        ActionResult result = actor.closeOutput("");
        assertThat(result.isSuccess()).isTrue();
    }
}
