package com.scivicslab.turingworkflow.plugins.inventory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.plugins.ssh.NodeActor;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link NodeGroupActor} against an inventory whose hosts all run commands on this machine
 * ({@code ansible_connection=local}), so no SSH connection is made.
 */
class NodeGroupActorTest {

    private static final String LOCAL_INVENTORY = """
        [web]
        web1 ansible_host=alpha ansible_connection=local
        web2 ansible_host=beta ansible_connection=local
        """;

    private IIActorSystem system;
    private NodeGroupActor nodeGroup;

    @BeforeEach
    void setUp() throws IOException {
        system = new IIActorSystem("node-group-actor-test");
        NodeGroup ng = new NodeGroup();
        ng.loadInventory(new ByteArrayInputStream(LOCAL_INVENTORY.getBytes(StandardCharsets.UTF_8)));
        nodeGroup = new NodeGroupActor("nodeGroup", new NodeGroupInterpreter(ng, system), system);
        system.addIIActor(nodeGroup);
    }

    @AfterEach
    void tearDown() {
        system.terminate();
    }

    @Nested
    @DisplayName("createNodeActors")
    class CreateNodeActors {

        @Test
        @DisplayName("registers one NodeActor per host, named node-<ansible_host>, as a child of the group")
        void createsOneNodeActorPerHost() {
            ActionResult result = nodeGroup.callByActionName("createNodeActors", "{\"group\":\"web\"}");

            assertThat(result.isSuccess()).as(result.getResult()).isTrue();
            assertThat(nodeGroup.getNamesOfChildren()).containsExactly("node-alpha", "node-beta");
            assertThat(system.getIIActor("node-alpha")).isInstanceOf(NodeActor.class);
            assertThat(system.getIIActor("node-beta")).isInstanceOf(NodeActor.class);
            assertThat(system.getIIActor("node-alpha").getParentName()).isEqualTo("nodeGroup");
        }

        @Test
        @DisplayName("a second call for the same group keeps the existing node actors")
        void secondCallSkipsExistingNodes() {
            nodeGroup.callByActionName("createNodeActors", "{\"group\":\"web\"}");
            var first = system.getIIActor("node-alpha");

            ActionResult result = nodeGroup.callByActionName("createNodeActors", "{\"group\":\"web\"}");

            assertThat(result.isSuccess()).as(result.getResult()).isTrue();
            assertThat(nodeGroup.getNamesOfChildren()).containsExactly("node-alpha", "node-beta");
            assertThat(system.getIIActor("node-alpha")).isSameAs(first);
        }

        @Test
        @DisplayName("a name held by an actor outside the group is reported as a failure")
        void nameHeldByAnotherActorFails() {
            system.addIIActor(new NodeActor("node-alpha",
                new com.scivicslab.turingworkflow.plugins.ssh.NodeInterpreter(
                    new com.scivicslab.turingworkflow.plugins.ssh.Node("alpha", "u", 22, true), system),
                system));

            ActionResult result = nodeGroup.callByActionName("createNodeActors", "{\"group\":\"web\"}");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResult()).contains("node-alpha");
            assertThat(nodeGroup.getNamesOfChildren()).doesNotContain("node-alpha");
        }
    }

    @Nested
    @DisplayName("apply")
    class Apply {

        @BeforeEach
        void createNodes() {
            nodeGroup.callByActionName("createNodeActors", "{\"group\":\"web\"}");
        }

        @Test
        @DisplayName("passes a map of arguments to each child as a JSON object")
        void mapArgumentsReachNamedFieldAction() {
            ActionResult result = nodeGroup.callByActionName("apply",
                "{\"actor\":\"node-*\",\"method\":\"executeCommand\",\"arguments\":{\"command\":\"echo applied\"}}");

            assertThat(result.isSuccess()).as(result.getResult()).isTrue();
            assertThat(result.getResult()).isEqualTo("Applied to 2 actors");
        }

        @Test
        @DisplayName("passes a list of arguments to each child as a JSON array")
        void listArgumentsStayArray() {
            // executeCommand declares named fields, so an array is refused by each child.
            ActionResult result = nodeGroup.callByActionName("apply",
                "{\"actor\":\"node-*\",\"method\":\"executeCommand\",\"arguments\":[\"echo applied\"]}");

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getResult()).startsWith("Applied to 0/2 actors");
        }
    }

    @Nested
    @DisplayName("executeCommandOnAllNodes")
    class ExecuteCommandOnAllNodes {

        @BeforeEach
        void createNodes() {
            nodeGroup.callByActionName("createNodeActors", "{\"group\":\"web\"}");
        }

        @Test
        @DisplayName("runs the command on every node")
        void runsCommandOnEveryNode() {
            ActionResult result = nodeGroup.callByActionName("executeCommandOnAllNodes",
                "{\"command\":\"echo on-every-node\"}");

            assertThat(result.isSuccess()).as(result.getResult()).isTrue();
            assertThat(result.getResult())
                .contains("node-alpha: on-every-node")
                .contains("node-beta: on-every-node");
        }

        @Test
        @DisplayName("reports failure when the command fails on a node")
        void failingCommandFails() {
            ActionResult result = nodeGroup.callByActionName("executeCommandOnAllNodes",
                "{\"command\":\"exit 3\"}");

            assertThat(result.isSuccess()).as(result.getResult()).isFalse();
        }
    }
}
