package com.scivicslab.turingworkflow.plugins.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * A document list cannot make the node run a command of its own (chat-86, HIGH 7).
 *
 * <p>{@code cloneChangedDocuments}, {@code buildChangedDocuments} and {@code deployChangedDocuments} built shell
 * command lines such as {@code "rm -rf " + path} and {@code "git clone " + gitUrl + " " + path} from the lines of the
 * list, so a line with {@code ;} or {@code $(...)} in the path or the URL ran what followed. Each case here puts a
 * {@code touch} of a marker file in the list and asserts that the marker does not exist afterwards.</p>
 */
@Tag("NodeInterpreterShellInjection_261123_oo01")
@DisplayName("NodeInterpreter - a document list cannot add commands")
@Timeout(120)
class NodeInterpreterShellInjectionTest {

    @TempDir
    Path tmp;

    private NodeInterpreter interpreter;

    @BeforeEach
    void makeInterpreter() {
        interpreter = new NodeInterpreter(new Node("localhost", "nobody", 22, true), null);
    }

    private ActionResult clone(String listLine, String docName) throws Exception {
        Path list = tmp.resolve("docs.txt");
        Files.writeString(list, listLine + "\n");
        interpreter.addChangedDocument(docName);
        return interpreter.cloneChangedDocuments(list.toString());
    }

    @Test
    @DisplayName("a git URL followed by ; and a command does not run the command")
    void gitUrlWithACommand_isRefused() throws Exception {
        Path marker = tmp.resolve("marker-url");
        String path = tmp.resolve("work/doc-a").toString();

        ActionResult result = clone(path + " https://example.invalid/a.git; touch " + marker, "doc-a");

        assertThat(marker).as("the command in the URL ran").doesNotExist();
        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).contains("doc-a").contains("refused");
    }

    @Test
    @DisplayName("a document path with ; or $(...) does not run the command")
    void documentPathWithACommand_isRefused() throws Exception {
        Path marker = tmp.resolve("marker-path");
        for (String suffix : new String[] {";touch${IFS}" + marker + ";", "$(touch${IFS}" + marker + ")"}) {
            String path = tmp.resolve("work/doc") + suffix;
            String docName = Path.of(path).getFileName().toString();

            ActionResult clone = clone(path + " " + tmp.resolve("origin"), docName);

            assertThat(marker).as("the command in the path ran (clone), suffix " + suffix).doesNotExist();
            assertThat(clone.isSuccess()).isFalse();

            Path list = tmp.resolve("build.txt");
            Files.writeString(list, path + "\n");
            ActionResult build = interpreter.buildChangedDocuments(list.toString());

            assertThat(marker).as("the command in the path ran (build), suffix " + suffix).doesNotExist();
            assertThat(build.isSuccess()).isFalse();
        }
    }

    @Test
    @DisplayName("a git URL that starts with - is not read as an option")
    void gitUrlThatLooksLikeAnOption_isRefused() throws Exception {
        String path = tmp.resolve("work/doc-b").toString();

        ActionResult result = clone(path + " --upload-pack=touch", "doc-b");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("refused");
    }

    @Test
    @DisplayName("a document path that is not under a directory, or climbs out of it, is refused")
    void dangerousPaths_areRefused() {
        for (String bad : new String[] {"/", "~", "/home", "~/..", "/tmp/a/..", "/tmp/a/../b", "relative/doc", "-rf",
                "/tmp/doc name", "/tmp/doc\nname", "/tmp/doc'quote", "/tmp/doc`x`"}) {
            assertThatThrownBy(() -> ShellWords.checkDocumentPath(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        ShellWords.checkDocumentPath("/home/u/works/doc_Base010");
        ShellWords.checkDocumentPath("~/works/doc-a");
    }

    @Test
    @DisplayName("a git URL with a shell character, a space or a leading - is refused; the ordinary forms are not")
    void gitUrls() {
        for (String bad : new String[] {"https://h/a.git; touch x", "-oProxyCommand=x", "https://h/$(id)", "a b", "x`y`", "",
                "https://h/a|b", "ext::sh -c touch% x"}) {
            assertThatThrownBy(() -> ShellWords.checkGitUrl(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        for (String good : new String[] {"https://github.com/scivicslab/doc_Base010.git", "git@github.com:scivicslab/x.git",
                "ssh://git@host:2222/srv/x.git", "/srv/git/x.git", "file:///srv/git/x.git"}) {
            ShellWords.checkGitUrl(good);
        }
    }

    @Test
    @DisplayName("quoting keeps a ~ at the start of a path, so the node's shell still expands it")
    void quoting() {
        assertThat(ShellWords.path("~/works/doc-a")).isEqualTo("~/'works/doc-a'");
        assertThat(ShellWords.path("/home/u/works/doc-a")).isEqualTo("'/home/u/works/doc-a'");
        assertThat(ShellWords.quote("it's")).isEqualTo("'it'\\''s'");
    }
}
