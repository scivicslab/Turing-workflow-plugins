package com.scivicslab.turingworkflow.plugins.ssh;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Checks that cloning and building the changed documents is false when any document failed, naming each failed
 * document and why, while the documents that succeeded are done.
 *
 * <p>The node runs its commands locally. Cloning reads a local repository, so git is needed; building runs
 * {@code yarn}, which fails in a directory without a project (or where yarn is not installed) either way.</p>
 */
@DisplayName("NodeInterpreter - a document that failed makes clone and build false, naming it")
class NodeInterpreterDocumentsTest {

    @TempDir
    Path tmp;

    private NodeInterpreter interpreter;

    @BeforeEach
    void makeInterpreter() {
        interpreter = new NodeInterpreter(new Node("localhost", "nobody", 22, true), null);
    }

    @Test
    void cloneChangedDocuments_oneUrlMissing_isFalseNamingItAndClonesTheOther() throws Exception {
        Assumptions.assumeTrue(gitRuns(), "git is not installed; this test needs it");
        Path origin = tmp.resolve("origin");
        Files.createDirectories(origin);
        git(origin, "init", "-q");
        git(origin, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "--allow-empty", "-m", "init");
        Path list = tmp.resolve("docs.txt");
        Files.writeString(list, tmp.resolve("work/doc-a") + " " + tmp.resolve("no-such-repository") + "\n"
                + tmp.resolve("work/doc-b") + " " + origin + "\n");
        interpreter.addChangedDocument("doc-a");
        interpreter.addChangedDocument("doc-b");

        ActionResult result = interpreter.cloneChangedDocuments(list.toString());

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).startsWith("1 of 2 failed: doc-a: git clone failed (exit 128)").endsWith("Cloned 1 documents");
        assertThat(tmp.resolve("work/doc-b/.git")).exists();
    }

    @Test
    void buildChangedDocuments_buildFails_isFalseNamingTheDocument() throws Exception {
        Path doc = tmp.resolve("doc-c");
        Files.createDirectories(doc);
        Path list = tmp.resolve("docs.txt");
        Files.writeString(list, doc + "\n");
        interpreter.addChangedDocument("doc-c");

        ActionResult result = interpreter.buildChangedDocuments(list.toString());

        assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        assertThat(result.getResult()).startsWith("1 of 1 failed: doc-c: yarn install && yarn build failed (exit ")
                .endsWith("Built 0 documents");
    }

    @Test
    void buildChangedDocuments_nothingChanged_isTrue() throws Exception {
        Path list = tmp.resolve("docs.txt");
        Files.writeString(list, tmp.resolve("doc-d") + "\n");

        ActionResult result = interpreter.buildChangedDocuments(list.toString());

        assertThat(result.isSuccess()).isTrue();
    }

    private static boolean gitRuns() {
        try {
            Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
    }
}
