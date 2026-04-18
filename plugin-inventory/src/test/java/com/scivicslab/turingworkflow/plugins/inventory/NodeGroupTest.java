package com.scivicslab.turingworkflow.plugins.inventory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.scivicslab.turingworkflow.plugins.ssh.Node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeGroupTest {

    private NodeGroup loadFromString(String iniContent) throws IOException {
        NodeGroup ng = new NodeGroup();
        ng.loadInventory(new ByteArrayInputStream(iniContent.getBytes(StandardCharsets.UTF_8)));
        return ng;
    }

    @Nested
    @DisplayName("createNodesForGroup")
    class CreateNodesForGroup {

        @Test
        @DisplayName("creates nodes for a group with host vars")
        void basicGroup() throws IOException {
            String ini = """
                [web]
                server1 ansible_host=192.168.1.10
                server2 ansible_host=192.168.1.11
                """;

            NodeGroup ng = loadFromString(ini);
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes).hasSize(2);
            assertThat(nodes.get(0).getHostname()).isEqualTo("192.168.1.10");
            assertThat(nodes.get(1).getHostname()).isEqualTo("192.168.1.11");
        }

        @Test
        @DisplayName("applies global vars as defaults")
        void globalVars() throws IOException {
            String ini = """
                [all:vars]
                ansible_user=admin
                ansible_port=2222

                [web]
                server1
                """;

            NodeGroup ng = loadFromString(ini);
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).getUser()).isEqualTo("admin");
            assertThat(nodes.get(0).getPort()).isEqualTo(2222);
        }

        @Test
        @DisplayName("host vars override group vars override global vars")
        void varPrecedence() throws IOException {
            String ini = """
                [all:vars]
                ansible_user=global_user
                ansible_port=1111

                [web:vars]
                ansible_user=group_user
                ansible_port=2222

                [web]
                server1 ansible_user=host_user
                """;

            NodeGroup ng = loadFromString(ini);
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes.get(0).getUser()).isEqualTo("host_user");
            assertThat(nodes.get(0).getPort()).isEqualTo(2222);
        }

        @Test
        @DisplayName("actoriac_ prefix takes precedence over ansible_")
        void actoriacPrecedence() throws IOException {
            String ini = """
                [all:vars]
                ansible_user=ansible_val
                actoriac_user=actoriac_val

                [web]
                server1
                """;

            NodeGroup ng = loadFromString(ini);
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes.get(0).getUser()).isEqualTo("actoriac_val");
        }

        @Test
        @DisplayName("throws IllegalStateException if inventory not loaded")
        void noInventory() {
            NodeGroup ng = new NodeGroup();

            assertThatThrownBy(() -> ng.createNodesForGroup("web"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Inventory not loaded");
        }

        @Test
        @DisplayName("local connection mode sets localMode=true")
        void localConnection() throws IOException {
            String ini = """
                [local]
                localhost ansible_connection=local
                """;

            NodeGroup ng = loadFromString(ini);
            List<Node> nodes = ng.createNodesForGroup("local");

            assertThat(nodes.get(0).isLocalMode()).isTrue();
        }
    }

    @Nested
    @DisplayName("Host limit")
    class HostLimit {

        @Test
        @DisplayName("limits nodes to specified hosts")
        void limitHosts() throws IOException {
            String ini = """
                [web]
                server1
                server2
                server3
                """;

            NodeGroup ng = loadFromString(ini);
            ng.setHostLimit("server1,server3");
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes).hasSize(2);
        }

        @Test
        @DisplayName("null limit means no filtering")
        void nullLimit() throws IOException {
            String ini = """
                [web]
                server1
                server2
                """;

            NodeGroup ng = loadFromString(ini);
            ng.setHostLimit(null);
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes).hasSize(2);
        }

        @Test
        @DisplayName("empty limit string clears the limit")
        void emptyLimit() throws IOException {
            String ini = """
                [web]
                server1
                server2
                """;

            NodeGroup ng = loadFromString(ini);
            ng.setHostLimit("server1");
            ng.setHostLimit("");
            List<Node> nodes = ng.createNodesForGroup("web");

            assertThat(nodes).hasSize(2);
        }
    }

    @Nested
    @DisplayName("createLocalNode")
    class CreateLocalNode {

        @Test
        @DisplayName("creates a single localhost node")
        void localNode() {
            NodeGroup ng = new NodeGroup();
            List<Node> nodes = ng.createLocalNode();

            assertThat(nodes).hasSize(1);
            assertThat(nodes.get(0).getHostname()).isEqualTo("localhost");
            assertThat(nodes.get(0).isLocalMode()).isTrue();
            assertThat(nodes.get(0).getPort()).isEqualTo(22);
        }
    }

    @Nested
    @DisplayName("Builder pattern")
    class BuilderPattern {

        @Test
        @DisplayName("builds NodeGroup via Builder")
        void builder() throws IOException {
            String ini = """
                [web]
                host1
                """;

            NodeGroup ng = new NodeGroup.Builder()
                .withInventory(new ByteArrayInputStream(ini.getBytes(StandardCharsets.UTF_8)))
                .build();

            assertThat(ng.getInventory()).isNotNull();
            assertThat(ng.createNodesForGroup("web")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Parse warnings")
    class ParseWarnings {

        @Test
        @DisplayName("reports parse warnings from inventory")
        void hasWarnings() throws IOException {
            String ini = """
                [all:children]
                web
                """;

            NodeGroup ng = loadFromString(ini);
            assertThat(ng.hasParseWarnings()).isTrue();
            assertThat(ng.getParseWarnings()).isNotEmpty();
        }

        @Test
        @DisplayName("no warnings for clean inventory")
        void noWarnings() throws IOException {
            String ini = """
                [web]
                host1
                """;

            NodeGroup ng = loadFromString(ini);
            assertThat(ng.hasParseWarnings()).isFalse();
        }
    }
}
