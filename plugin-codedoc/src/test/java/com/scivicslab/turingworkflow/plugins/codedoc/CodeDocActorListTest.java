package com.scivicslab.turingworkflow.plugins.codedoc;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeDocActor answering the lists it finds as JSON arrays, and taking the lists and texts it uses as values.
 * A workflow keeps them in its own state; the engine's {@code list} and {@code str} actors were removed in 5.0.0.
 */
class CodeDocActorListTest {

    @TempDir
    Path dir;

    private IIActorSystem system;
    private CodeDocActor codedoc;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("codedoc-test");
        codedoc = new CodeDocActor("codedoc", system);
        system.addIIActor(codedoc);
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
        system.terminate();
    }

    /** The JSON array a list-answering action returned. */
    private JSONArray listOf(ActionResult r) {
        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        return new JSONArray(r.getResult());
    }

    @Test
    @DisplayName("chunkFile answers every chunk")
    void chunkFileAnswersChunks() throws IOException {
        Path md = dir.resolve("doc.md");
        Files.writeString(md, "# Title\n\nbody text\n");

        JSONArray chunks = listOf(codedoc.callByActionName("chunkFile",
                new JSONObject().put("file", md.toString()).toString()));

        assertThat(chunks.length()).isPositive();
        assertThat(chunks.getString(0)).contains("body text");
    }

    @Test
    @DisplayName("scanProject answers each file")
    void scanProjectAnswersFiles() throws IOException {
        Files.writeString(dir.resolve("A.java"), "class A {}\n");

        JSONArray files = listOf(codedoc.callByActionName("scanProject",
                new JSONObject().put("directory", dir.toString()).toString()));

        assertThat(files.length()).isEqualTo(1);
        assertThat(files.getString(0)).startsWith("FILE: A.java").contains("class A {}");
    }

    @Test
    @DisplayName("listMarkdownNoDescription answers each path")
    void listMarkdownNoDescriptionAnswersPaths() throws IOException {
        Path md = dir.resolve("plain.md");
        Files.writeString(md, "# No frontmatter\n");

        JSONArray paths = listOf(codedoc.callByActionName("listMarkdownNoDescription",
                new JSONObject().put("directory", dir.toString()).toString()));

        assertThat(paths.length()).isEqualTo(1);
        assertThat(paths.getString(0)).isEqualTo(md.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("writeFileFromList writes the items, not an error text")
    void writeFileFromListWritesItems() throws IOException {
        Path out = dir.resolve("out.md");

        ActionResult r = codedoc.callByActionName("writeFileFromList", new JSONObject()
                .put("file", out.toString())
                .put("items", new JSONArray().put("first").put("second"))
                .put("separator", "|").toString());

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(Files.readString(out)).isEqualTo("first|second");
    }

    @Test
    @DisplayName("joinListToStr answers the joined items")
    void joinListToStrAnswersJoinedText() {
        ActionResult r = codedoc.callByActionName("joinListToStr", new JSONObject()
                .put("items", new JSONArray().put("first").put("second"))
                .put("separator", "|").toString());

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(r.getResult()).isEqualTo("first|second");
    }

    @Test
    @DisplayName("readFileToStr answers the content, kept as it is even when it is a JSON array")
    void readFileToStrKeepsJsonArrayContent() throws IOException {
        Path f = dir.resolve("data.json");
        Files.writeString(f, "[\"a\", \"b\"]");

        ActionResult r = codedoc.callByActionName("readFileToStr",
                new JSONObject().put("file", f.toString()).toString());

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(r.getResult()).isEqualTo("[\"a\", \"b\"]");
    }

    @Test
    @DisplayName("writeStrToFile writes the content it is given")
    void writeStrToFileWritesContent() throws IOException {
        Path out = dir.resolve("sub/out.md");

        ActionResult r = codedoc.callByActionName("writeStrToFile", new JSONObject()
                .put("file", out.toString()).put("content", "# Title\n").toString());

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(r.getResult()).isEqualTo(out.toString());
        assertThat(Files.readString(out)).isEqualTo("# Title\n");
    }

    @Test
    @DisplayName("setFrontmatterDescription inserts the description it is given")
    void setFrontmatterDescriptionInsertsDescription() throws IOException {
        Path md = dir.resolve("doc.md");
        Files.writeString(md, "# Title\n");

        ActionResult r = codedoc.callByActionName("setFrontmatterDescription", new JSONObject()
                .put("file", md.toString()).put("description", "What it is.").toString());

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(Files.readString(md)).isEqualTo("---\ndescription: |\n  What it is.\n---\n# Title\n");
    }

    @Test
    @DisplayName("an action that cannot do its work is false, with the reason")
    void failureIsFalseWithReason() {
        Path missing = dir.resolve("missing.md");

        ActionResult r = codedoc.callByActionName("readFileToStr",
                new JSONObject().put("file", missing.toString()).toString());

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getResult()).startsWith("readFileToStr: ").contains("missing.md");
    }
}
