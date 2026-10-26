package com.scivicslab.turingworkflow.plugins.codedoc;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.ListActor;
import com.scivicslab.turingworkflow.workflow.StringActor;
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
 * CodeDocActor working with the engine's built-in ListActor and StringActor.
 */
class CodeDocActorListTest {

    @TempDir
    Path dir;

    private IIActorSystem system;
    private ListActor list;
    private StringActor str;
    private CodeDocActor codedoc;

    @BeforeEach
    void setUp() {
        system = new IIActorSystem("codedoc-test");
        list = new ListActor("chunks", system);
        str = new StringActor("text", system);
        codedoc = new CodeDocActor("codedoc", system);
        system.addIIActor(list);
        system.addIIActor(str);
        system.addIIActor(codedoc);
    }

    @AfterEach
    void tearDown() {
        system.terminateIIActors();
        system.terminate();
    }

    private String listItem(int index) {
        ActionResult r = list.callByActionName("get", "{\"index\":" + index + "}");
        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        return r.getResult();
    }

    private void addToList(String value) {
        ActionResult r = list.add(new ListActor.ValueArgs(value));
        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
    }

    @Test
    @DisplayName("chunkFile adds every chunk to the list")
    void chunkFileFillsList() throws IOException {
        Path md = dir.resolve("doc.md");
        Files.writeString(md, "# Title\n\nbody text\n");

        ActionResult r = codedoc.callByActionName("chunkFile",
                "{\"file\":\"" + md + "\",\"listActor\":\"chunks\"}");

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(list.callByActionName("size", "").getResult()).isEqualTo(r.getResult());
        assertThat(listItem(0)).contains("body text");
    }

    @Test
    @DisplayName("scanProject adds each file to the list")
    void scanProjectFillsList() throws IOException {
        Files.writeString(dir.resolve("A.java"), "class A {}\n");

        ActionResult r = codedoc.callByActionName("scanProject",
                "{\"directory\":\"" + dir + "\",\"listActor\":\"chunks\"}");

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(r.getResult()).isEqualTo("1");
        assertThat(list.callByActionName("size", "").getResult()).isEqualTo("1");
        assertThat(listItem(0)).startsWith("FILE: A.java").contains("class A {}");
    }

    @Test
    @DisplayName("listMarkdownNoDescription adds each path to the list")
    void listMarkdownNoDescriptionFillsList() throws IOException {
        Path md = dir.resolve("plain.md");
        Files.writeString(md, "# No frontmatter\n");

        ActionResult r = codedoc.callByActionName("listMarkdownNoDescription",
                "{\"directory\":\"" + dir + "\",\"listActor\":\"chunks\"}");

        assertThat(r.getResult()).isEqualTo("1");
        assertThat(listItem(0)).isEqualTo(md.toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("writeFileFromList writes the items, not an error text")
    void writeFileFromListWritesItems() throws IOException {
        addToList("first");
        addToList("second");
        Path out = dir.resolve("out.md");

        ActionResult r = codedoc.callByActionName("writeFileFromList",
                "{\"file\":\"" + out + "\",\"listActor\":\"chunks\",\"separator\":\"|\"}");

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(Files.readString(out)).isEqualTo("first|second");
    }

    @Test
    @DisplayName("joinListToStr stores the joined items in the string actor")
    void joinListToStrStoresItems() {
        addToList("first");
        addToList("second");

        ActionResult r = codedoc.callByActionName("joinListToStr",
                "{\"listActor\":\"chunks\",\"strActor\":\"text\",\"separator\":\"|\"}");

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(str.callByActionName("get", "").getResult()).isEqualTo("first|second");
    }

    @Test
    @DisplayName("readFileToStr keeps content that is itself a JSON array")
    void readFileToStrKeepsJsonArrayContent() throws IOException {
        Path f = dir.resolve("data.json");
        Files.writeString(f, "[\"a\", \"b\"]");

        ActionResult r = codedoc.callByActionName("readFileToStr",
                "{\"file\":\"" + f + "\",\"strActor\":\"text\"}");

        assertThat(r.isSuccess()).as(r.getResult()).isTrue();
        assertThat(str.callByActionName("get", "").getResult()).isEqualTo("[\"a\", \"b\"]");
    }
}
