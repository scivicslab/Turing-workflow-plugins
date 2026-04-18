package com.scivicslab.turingworkflow.plugins.inventory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryParserTest {

    private InputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("Group parsing")
    class GroupParsing {

        @Test
        @DisplayName("parses a simple group with hosts")
        void simpleGroup() throws IOException {
            String ini = """
                [webservers]
                web1
                web2
                web3
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            InventoryParser.Inventory inv = result.getInventory();

            assertThat(result.hasWarnings()).isFalse();
            assertThat(inv.getHosts("webservers")).containsExactly("web1", "web2", "web3");
        }

        @Test
        @DisplayName("parses multiple groups")
        void multipleGroups() throws IOException {
            String ini = """
                [webservers]
                web1
                web2

                [dbservers]
                db1
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();

            assertThat(inv.getHosts("webservers")).containsExactly("web1", "web2");
            assertThat(inv.getHosts("dbservers")).containsExactly("db1");
        }

        @Test
        @DisplayName("returns empty list for unknown group")
        void unknownGroup() throws IOException {
            String ini = """
                [webservers]
                web1
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();
            assertThat(inv.getHosts("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("empty group has no hosts")
        void emptyGroup() throws IOException {
            String ini = "[emptygroup]\n";

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();
            assertThat(inv.getHosts("emptygroup")).isEmpty();
            assertThat(inv.getAllGroups()).containsKey("emptygroup");
        }
    }

    @Nested
    @DisplayName("Comments and blank lines")
    class CommentsAndBlanks {

        @Test
        @DisplayName("skips comment lines starting with #")
        void hashComments() throws IOException {
            String ini = """
                # This is a comment
                [web]
                # Another comment
                host1
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();
            assertThat(inv.getHosts("web")).containsExactly("host1");
        }

        @Test
        @DisplayName("skips comment lines starting with ;")
        void semicolonComments() throws IOException {
            String ini = """
                ; This is a comment
                [web]
                host1
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();
            assertThat(inv.getHosts("web")).containsExactly("host1");
        }

        @Test
        @DisplayName("skips blank lines")
        void blankLines() throws IOException {
            String ini = """
                [web]

                host1

                host2
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();
            assertThat(inv.getHosts("web")).containsExactly("host1", "host2");
        }
    }

    @Nested
    @DisplayName("Variables")
    class Variables {

        @Test
        @DisplayName("parses global vars from [all:vars]")
        void globalVars() throws IOException {
            String ini = """
                [all:vars]
                ansible_user=admin
                ansible_port=2222

                [web]
                host1
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();

            Map<String, String> globalVars = inv.getGlobalVars();
            assertThat(globalVars).containsEntry("ansible_user", "admin");
            assertThat(globalVars).containsEntry("ansible_port", "2222");
        }

        @Test
        @DisplayName("parses group vars from [group:vars]")
        void groupVars() throws IOException {
            String ini = """
                [web]
                host1

                [web:vars]
                actoriac_user=deploy
                actoriac_port=22
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();

            Map<String, String> groupVars = inv.getGroupVars("web");
            assertThat(groupVars).containsEntry("actoriac_user", "deploy");
            assertThat(groupVars).containsEntry("actoriac_port", "22");
        }

        @Test
        @DisplayName("parses inline host variables")
        void hostVars() throws IOException {
            String ini = """
                [web]
                host1 ansible_host=192.168.1.10 ansible_port=2222
                host2 ansible_host=192.168.1.11
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();

            Map<String, String> h1Vars = inv.getHostVars("host1");
            assertThat(h1Vars).containsEntry("ansible_host", "192.168.1.10");
            assertThat(h1Vars).containsEntry("ansible_port", "2222");

            Map<String, String> h2Vars = inv.getHostVars("host2");
            assertThat(h2Vars).containsEntry("ansible_host", "192.168.1.11");
        }
    }

    @Nested
    @DisplayName("Warnings for unsupported features")
    class Warnings {

        @Test
        @DisplayName("warns on :children groups")
        void childrenWarning() throws IOException {
            String ini = """
                [all:children]
                web
                db
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings().get(0)).contains(":children");
        }

        @Test
        @DisplayName("warns on range patterns")
        void rangeWarning() throws IOException {
            String ini = """
                [web]
                web[01:50].example.com
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings().get(0)).contains("Range patterns");
        }

        @Test
        @DisplayName("warns on unsupported ansible_become")
        void becomeWarning() throws IOException {
            String ini = """
                [all:vars]
                ansible_become=true
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings().get(0)).contains("ansible_become");
            assertThat(result.getWarnings().get(0)).contains("SUDO_PASSWORD");
        }

        @Test
        @DisplayName("warns on ansible_python_interpreter")
        void pythonInterpreterWarning() throws IOException {
            String ini = """
                [all:vars]
                ansible_python_interpreter=/usr/bin/python3
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings().get(0)).contains("ansible_python_interpreter");
            assertThat(result.getWarnings().get(0)).contains("without Python");
        }

        @Test
        @DisplayName("warns on ansible_ssh_ variables")
        void sshVarWarning() throws IOException {
            String ini = """
                [all:vars]
                ansible_ssh_private_key_file=~/.ssh/mykey
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings().get(0)).contains("~/.ssh/config");
        }

        @Test
        @DisplayName("warns on unrecognized actoriac_ variables")
        void unrecognizedActoriacVar() throws IOException {
            String ini = """
                [all:vars]
                actoriac_unknown_var=something
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.getWarnings().get(0)).contains("not a recognized variable");
        }

        @Test
        @DisplayName("deduplicates warnings for same variable")
        void deduplicateWarnings() throws IOException {
            String ini = """
                [web]
                host1 ansible_become=true
                host2 ansible_become=true
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            long becomeWarnings = result.getWarnings().stream()
                .filter(w -> w.contains("ansible_become"))
                .count();
            assertThat(becomeWarnings).isEqualTo(1);
        }

        @Test
        @DisplayName("no warnings for supported variables")
        void supportedVarsNoWarning() throws IOException {
            String ini = """
                [all:vars]
                ansible_host=192.168.1.1
                ansible_user=admin
                ansible_port=22
                ansible_connection=ssh
                actoriac_host=10.0.0.1
                actoriac_user=deploy
                actoriac_port=2222
                actoriac_connection=local
                """;

            InventoryParser.ParseResult result = InventoryParser.parse(toStream(ini));
            assertThat(result.hasWarnings()).isFalse();
        }
    }

    @Nested
    @DisplayName("getAllGroups")
    class AllGroups {

        @Test
        @DisplayName("returns all defined groups")
        void allGroups() throws IOException {
            String ini = """
                [web]
                host1
                [db]
                host2
                [cache]
                host3
                """;

            InventoryParser.Inventory inv = InventoryParser.parse(toStream(ini)).getInventory();
            assertThat(inv.getAllGroups().keySet()).containsExactlyInAnyOrder("web", "db", "cache");
        }
    }
}
