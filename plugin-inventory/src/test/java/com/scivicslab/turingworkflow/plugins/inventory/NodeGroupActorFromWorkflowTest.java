package com.scivicslab.turingworkflow.plugins.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.DynamicActorLoaderIIAR;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.scivicslab.turingworkflow.workflow.Interpreter;
import com.scivicslab.turingworkflow.workflow.InterpreterIIAR;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulator;
import com.scivicslab.turingworkflow.workflow.accumulator.MultiplexerAccumulatorIIAR;

/**
 * A workflow makes its node group itself: {@code loader.createChild} makes the
 * {@code NodeGroupActor}, and {@code loadInventory} reads the inventory file the workflow names,
 * under the workflow's base directory. {@code createAccumulator} says whether there is a
 * multiplexer to collect into.
 */
@DisplayName("NodeGroupActor made by a workflow")
class NodeGroupActorFromWorkflowTest {

    private static final String NODE_GROUP = "com.scivicslab.turingworkflow.plugins.inventory.NodeGroupActor";
    private static final String INVENTORY = "[single]\nlocalhost ansible_connection=local\n";

    @TempDir
    Path dir;

    private Path base;
    private IIActorSystem system;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        base = Files.createDirectory(dir.resolve("base"));
        system = new IIActorSystem("node-group-from-workflow");
        interpreter = new Interpreter.Builder().loggerName("interpreter").team(system).build();
        interpreter.setWorkflowBaseDir(base.toString());
        InterpreterIIAR self = new InterpreterIIAR("interpreter", interpreter, system);
        interpreter.setSelfActorRef(self);
        system.addIIActor(self);
        system.addIIActor(new DynamicActorLoaderIIAR("loader", system));
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Test
    @DisplayName("loader.createChild, then loadInventory with the file under the base directory")
    void workflowMakesItsNodeGroup() throws Exception {
        Files.writeString(base.resolve("inventory.ini"), INVENTORY);
        interpreter.readYaml(new ByteArrayInputStream(("""
            name: makes-node-group
            steps:
              - states: ["0", "1"]
                actions:
                  - actor: loader
                    method: createChild
                    arguments: ["interpreter", "nodeGroup", "%s"]
              - states: ["1", "2"]
                actions:
                  - actor: nodeGroup
                    method: loadInventory
                    arguments: {path: "inventory.ini"}
              - states: ["2", "3"]
                actions:
                  - actor: nodeGroup
                    method: hasInventory
              - states: ["3", "end"]
                actions:
                  - actor: nodeGroup
                    method: createNodeActors
                    arguments: {group: "single"}
            """.formatted(NODE_GROUP)).getBytes(StandardCharsets.UTF_8)));

        ActionResult result = interpreter.runUntilEnd(10);

        assertThat(result.isSuccess()).as(result.getResult()).isTrue();
        assertThat(system.getIIActor("nodeGroup")).isInstanceOf(NodeGroupActor.class);
        assertThat(system.getIIActor("node-localhost")).isNotNull();
    }

    @Test
    @DisplayName("an inventory path outside the base directory is refused")
    void inventoryOutsideBaseIsRefused() throws Exception {
        Files.writeString(dir.resolve("outside.ini"), INVENTORY);
        IIActorRef<?> nodeGroup = createNodeGroup();

        ActionResult result = nodeGroup.callByActionName("loadInventory", "{\"path\":\"../outside.ini\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("outside the base directory");
        assertThat(nodeGroup.callByActionName("hasInventory", "").isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a link inside the base directory that points outside it is refused")
    void linkOutOfBaseIsRefused() throws Exception {
        Files.writeString(dir.resolve("outside.ini"), INVENTORY);
        Files.createSymbolicLink(base.resolve("link.ini"), dir.resolve("outside.ini"));
        IIActorRef<?> nodeGroup = createNodeGroup();

        ActionResult result = nodeGroup.callByActionName("loadInventory", "{\"path\":\"link.ini\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getResult()).contains("outside the base directory");
    }

    @Test
    @DisplayName("createAccumulator is false with no multiplexer to collect into, true with one")
    void createAccumulatorSaysWhetherThereIsATarget() throws Exception {
        NodeGroupActor nodeGroup = new NodeGroupActor("nodeGroup", new NodeGroupInterpreter(new NodeGroup(), system), system);
        system.addIIActor(nodeGroup);

        ActionResult without = nodeGroup.callByActionName("createAccumulator", "");
        assertThat(without.isSuccess()).as(without.getResult()).isFalse();
        assertThat(without.getResult()).contains("outputMultiplexer");

        system.addIIActor(new MultiplexerAccumulatorIIAR("outputMultiplexer", new MultiplexerAccumulator(), system));
        ActionResult with = nodeGroup.callByActionName("createAccumulator", "");
        assertThat(with.isSuccess()).as(with.getResult()).isTrue();
    }

    private IIActorRef<?> createNodeGroup() {
        ActionResult created = system.getIIActor("loader").callByActionName("createChild",
            "[\"interpreter\", \"nodeGroup\", \"" + NODE_GROUP + "\"]");
        assertThat(created.isSuccess()).as(created.getResult()).isTrue();
        return system.getIIActor("nodeGroup");
    }
}
