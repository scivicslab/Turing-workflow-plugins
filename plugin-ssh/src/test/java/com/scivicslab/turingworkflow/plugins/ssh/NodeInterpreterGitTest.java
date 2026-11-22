package com.scivicslab.turingworkflow.plugins.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;

/**
 * Checks that {@code detectDocumentChanges} does not report a document as up to date when {@code git fetch} failed
 * or did not finish, and that a fetch that does not finish is ended at its limit.
 *
 * <p>Each test makes a local git repository whose {@code origin/main} already equals {@code HEAD}, so only a
 * successful fetch could justify "up to date".</p>
 */
@DisplayName("NodeInterpreter - git fetch is bounded and its failure is not reported as up to date")
class NodeInterpreterGitTest {

    @TempDir
    Path tmp;

    private Path doc;
    private Path list;
    private NodeInterpreter interpreter;

    @BeforeEach
    void makeRepository() throws Exception {
        doc = tmp.resolve("doc");
        Files.createDirectories(doc);
        git(doc, "init", "-q");
        git(doc, "-c", "user.name=test", "-c", "user.email=test@example.com", "commit", "-q", "--allow-empty", "-m", "init");
        git(doc, "update-ref", "refs/remotes/origin/main", "HEAD");
        list = tmp.resolve("docs.txt");
        Files.writeString(list, doc + " unused-url\n");
        interpreter = new NodeInterpreter(new Node("localhost", "nobody", 22, true), null);
    }

    @AfterEach
    void clearLimit() {
        System.clearProperty(NodeInterpreter.GIT_TIMEOUT_PROPERTY);
    }

    @Test
    void detectDocumentChanges_fetchFails_documentIsNotUpToDate() throws Exception {
        git(doc, "remote", "add", "origin", tmp.resolve("no-such-repository").toString());

        ActionResult result = interpreter.detectDocumentChanges(list.toString());

        assertThat(result.isSuccess()).isTrue();
        assertThat(interpreter.isDocumentChanged("doc")).isTrue();
    }

    @Test
    void detectDocumentChanges_fetchNeverAnswered_endsAtLimitAndDocumentIsNotUpToDate() throws Exception {
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) accepted.add(server.accept());
                } catch (IOException e) {
                    // the server socket was closed at the end of the test
                }
            });
            git(doc, "remote", "add", "origin", "git://127.0.0.1:" + server.getLocalPort() + "/doc");
            System.setProperty(NodeInterpreter.GIT_TIMEOUT_PROPERTY, "1");

            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> interpreter.detectDocumentChanges(list.toString()));

            assertThat(interpreter.isDocumentChanged("doc")).isTrue();
        } finally {
            for (Socket s : accepted) s.close();
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
