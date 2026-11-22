package com.scivicslab.turingworkflow.plugins.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.pojoactor.core.accumulator.Accumulator;
import com.scivicslab.turingworkflow.cli.RunCLI;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;

/**
 * Output a node produces reaches the output multiplexer the run command registers.
 *
 * <p>chat-87's reproduction (t1970): the CLI registered the multiplexer as {@code log}, the node
 * actors look it up as {@code outputMultiplexer}, so a node's {@code echo hello-from-node} never
 * reached it. The node here runs on this machine ({@code ansible_connection=local}); no ssh.</p>
 */
@DisplayName("a node's output reaches the run's output multiplexer")
class NodeOutputReachesMultiplexerTest {

    /** One entry the multiplexer handed on. */
    record Entry(String source, String type, String data) {}

    /** A multiplexer target that keeps what it is given. */
    static final class Kept implements Accumulator {
        final List<Entry> entries = new CopyOnWriteArrayList<>();

        @Override
        public void add(String source, String type, String data) {
            entries.add(new Entry(source, type, data));
        }

        @Override
        public String getSummary() {
            return entries.size() + " entries kept";
        }
    }

    @TempDir
    Path dir;

    private IIActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.terminate();
        }
    }

    @Test
    @DisplayName("[node-localhost] hello-from-node reaches the multiplexer")
    void nodeOutputReachesTheMultiplexer() throws Exception {
        system = new IIActorSystem("node-output-reaches-multiplexer");
        MultiplexerAccumulator mux = RunCLI.registerOutputMultiplexer(system);
        Kept kept = new Kept();
        mux.addTarget(kept);

        NodeGroup inventory = new NodeGroup();
        inventory.loadInventory(new ByteArrayInputStream(
            "[single]\nlocalhost ansible_connection=local\n".getBytes(StandardCharsets.UTF_8)));
        NodeGroupActor nodeGroup = new NodeGroupActor("nodeGroup", new NodeGroupInterpreter(inventory, system), system);
        system.addIIActor(nodeGroup);

        Path sub = dir.resolve("sub.yaml");
        Files.writeString(sub, """
            name: node-says-hello
            steps:
              - states: ["0", "end"]
                actions:
                  - actor: this
                    method: executeCommand
                    arguments:
                      command: echo hello-from-node
            """);

        ActionResult created = nodeGroup.callByActionName("createNodeActors", "{\"group\":\"single\"}");
        assertThat(created.isSuccess()).as(created.getResult()).isTrue();
        ActionResult applied = nodeGroup.callByActionName("apply",
            "{\"actor\":\"node-*\",\"method\":\"runWorkflow\",\"arguments\":{\"workflowFile\":\"" + sub + "\"}}");
        assertThat(applied.isSuccess()).as(applied.getResult()).isTrue();

        // The multiplexer takes each entry on its own mailbox virtual thread, after add returns.
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (kept.entries.stream().noneMatch(NodeOutputReachesMultiplexerTest::isHello)
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(kept.entries).as("entries the multiplexer handed on").anyMatch(NodeOutputReachesMultiplexerTest::isHello);
    }

    private static boolean isHello(Entry e) {
        return e.source().equals("node-localhost") && e.data().equals("hello-from-node");
    }
}
