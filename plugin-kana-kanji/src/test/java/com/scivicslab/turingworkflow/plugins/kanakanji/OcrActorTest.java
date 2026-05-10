package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.core.ActionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class OcrActorTest {

    static class StubOcrActor extends OcrActor {
        StubOcrActor() {
            super("test-ocr", null);
        }
    }

    private StubOcrActor actor;

    @BeforeEach
    void setUp() {
        actor = new StubOcrActor();
    }

    private Path createTsv(Path dir, String filename, String content) throws IOException {
        Path file = dir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void loadFile_withValidTsv_succeeds(@TempDir Path tempDir) throws IOException {
        // TSV format: hiragana TAB kanji TAB page
        Path tsv = createTsv(tempDir, "ocr.tsv",
                "hiragana\tkanji\tpage\n" +
                "\t天気\t1\n" +
                "\t晴れ\t1\n" +
                "\t風\t2\n");

        ActionResult result = actor.loadFile(tsv.toString());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains("2 pages");
    }

    @Test
    void loadFile_nonExistentFile_fails(@TempDir Path tempDir) {
        ActionResult result = actor.loadFile(tempDir.resolve("missing.tsv").toString());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("Failed to read");
    }

    @Test
    void loadFile_emptyPath_fails() {
        ActionResult result = actor.loadFile("");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("required");
    }

    @Test
    void nextPage_withoutLoadFile_fails() {
        ActionResult result = actor.nextPage("");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void nextPage_advancesThroughPages(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "ocr.tsv",
                "h\tk\tp\n" +
                "\t天気\t1\n" +
                "\t風\t2\n");
        actor.loadFile(tsv.toString());

        ActionResult page1 = actor.nextPage("");
        assertThat(page1.isSuccess()).isTrue();
        assertThat(page1.getResult()).contains("Page 1");

        ActionResult page2 = actor.nextPage("");
        assertThat(page2.isSuccess()).isTrue();
        assertThat(page2.getResult()).contains("Page 2");

        ActionResult exhausted = actor.nextPage("");
        assertThat(exhausted.isSuccess()).isFalse();
        assertThat(exhausted.getResult()).contains("No more pages");
    }

    @Test
    void getPageText_returnsJoinedLines(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "ocr.tsv",
                "h\tk\tp\n" +
                "\t天気晴朗\t1\n" +
                "\t波高し\t1\n");
        actor.loadFile(tsv.toString());
        actor.nextPage("");

        ActionResult result = actor.getPageText("");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("天気晴朗\n波高し");
    }

    @Test
    void getPageText_withoutNextPage_fails(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "ocr.tsv", "h\tk\tp\n\t天気\t1\n");
        actor.loadFile(tsv.toString());

        ActionResult result = actor.getPageText("");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("nextPage");
    }

    @Test
    void getPageInfo_returnsPageAndSourceFile(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "source.tsv", "h\tk\tp\n\t天気\t3\n");
        actor.loadFile(tsv.toString());
        actor.nextPage("");

        ActionResult result = actor.getPageInfo("");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).contains("3").contains("source.tsv");
    }

    @Test
    void getPageInfo_withoutNextPage_fails(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "ocr.tsv", "h\tk\tp\n\t天気\t1\n");
        actor.loadFile(tsv.toString());

        ActionResult result = actor.getPageInfo("");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void loadFile_skipsRowsWithNonNumericPage(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "ocr.tsv",
                "h\tk\tp\n" +
                "\t天気\t1\n" +
                "\t風\tabc\n" +  // non-numeric page — skipped
                "\t雨\t2\n");
        actor.loadFile(tsv.toString());

        ActionResult p1 = actor.nextPage("");
        assertThat(p1.isSuccess()).isTrue();
        ActionResult p2 = actor.nextPage("");
        assertThat(p2.isSuccess()).isTrue();
        assertThat(actor.nextPage("").isSuccess()).isFalse(); // only 2 pages
    }

    @Test
    void loadFile_skipsEmptyKanjiColumn(@TempDir Path tempDir) throws IOException {
        Path tsv = createTsv(tempDir, "ocr.tsv",
                "h\tk\tp\n" +
                "\t\t1\n" +      // empty kanji — skipped
                "\t天気\t1\n");
        actor.loadFile(tsv.toString());
        actor.nextPage("");

        ActionResult result = actor.getPageText("");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult()).isEqualTo("天気"); // only non-empty line
    }
}
