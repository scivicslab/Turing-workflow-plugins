/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.plugins.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.scivicslab.turingworkflow.plugins.ssh.Node;

/**
 * Generates {@link Node} objects from Ansible INI inventory files, applying variable precedence and connection
 * settings.
 *
 * <ul><li>{@link #createNodesForGroup(String)} returns a list of {@link Node} objects for the hosts in the named
 * group, applying global, group, and host-specific variables.</li><li>{@link #createLocalNode()} returns a single
 * {@link Node} configured for local execution.</li><li>{@link #getParseWarnings()} returns a list of messages
 * describing unsupported or problematic syntax found in the inventory file.</li><li>{@link #setHostLimit(String)}
 * restricts subsequent node creation to the specified comma-separated list of hostnames.</li><li>{@link
 * #setSshPassword(String)} sets a password to be used for all nodes created by this instance.</li></ul>
 *
 * <p>This instance holds the parsed inventory data, any parsing warnings, a common SSH password, and an optional host
 * limit.</p>
 *
 * <p>One instance may serve several workflows or threads at once, provided the underlying {@link Node} objects are
 * used to create independent actors.</p>
 *
 * <pre>{@code
 * NodeGroup ng = new NodeGroup.Builder()
 *     .withInventory(new FileInputStream("inventory.ini"))
 *     .build();
 * List<Node> nodes = ng.createNodesForGroup("webservers");
 * }</pre>
 *
 * <p>If the inventory has not been loaded, {@link #createNodesForGroup(String)} throws an {@link
 * IllegalStateException}.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class NodeGroup {

    private static final Logger logger = Logger.getLogger(NodeGroup.class.getName());

    private InventoryParser.Inventory inventory;
    private List<String> parseWarnings = new ArrayList<>();
    private String sshPassword;
    private List<String> hostLimit;

    /**
     * Provides a fluent API for configuring and creating {@link NodeGroup} instances.
     *
     * <p>Use this to set up an inventory and any other settings before producing a {@link NodeGroup}.
     *
     * <pre>{@code
     * NodeGroup nodeGroup = new NodeGroup.Builder()
     *     .withInventory(new FileInputStream("inventory.ini"))
     *     .build();
     * }</pre>
     *
     * <ul><li>{@link #withInventory(InputStream)} loads an Ansible inventory file and returns this builder for method
     * chaining.</li><li>{@link #build()} returns a new {@link NodeGroup} instance with the configured
     * settings.</li></ul>
     *
     * <p>If reading the inventory fails, {@link #withInventory(InputStream)} throws an {@link java.io.IOException}.
     *
     * @since 1.0
     */
    public static class Builder {
        private InventoryParser.Inventory inventory;
        private List<String> warnings = new ArrayList<>();

        /**
         * Parses an Ansible inventory file from the provided stream and configures the builder with the resulting
         * inventory and any parsing warnings.
         *
         * @param inventoryStream the input stream containing the inventory file
         * @return this builder for method chaining
         * @throws IOException if an error occurs while reading the stream
         */
        public Builder withInventory(InputStream inventoryStream) throws IOException {
            InventoryParser.ParseResult result = InventoryParser.parse(inventoryStream);
            this.inventory = result.getInventory();
            this.warnings = result.getWarnings();
            return this;
        }

        /**
         * Returns a new {@code NodeGroup} instance configured with the inventory and warnings provided during the
         * building process.
         */
        public NodeGroup build() {
            return new NodeGroup(inventory, warnings);
        }
    }

    /** Initializes an empty {@code NodeGroup} without any loaded inventory or configuration. */
    public NodeGroup() {
    }

    /**
     * Initializes a {@code NodeGroup} with a parsed inventory and its associated warnings.
     *
     * @param inventory the parsed inventory
     * @param warnings list of warnings from parsing
     */
    private NodeGroup(InventoryParser.Inventory inventory, List<String> warnings) {
        this.inventory = inventory;
        this.parseWarnings = warnings;
    }

    /**
     * Loads an Ansible inventory file from the provided input stream.
     *
     * <p>Any warnings generated during parsing, such as unsupported group nesting or range patterns, are stored and
     * can be retrieved via {@link #getParseWarnings()}.</p>
     *
     * @param inventoryStream the input stream containing the inventory file
     * @throws IOException if reading the inventory fails
     */
    public void loadInventory(InputStream inventoryStream) throws IOException {
        InventoryParser.ParseResult result = InventoryParser.parse(inventoryStream);
        this.inventory = result.getInventory();
        this.parseWarnings = result.getWarnings();
    }

    /**
     * Returns a list of warning messages generated during the parsing of the Ansible inventory file.
     *
     * @return a list of strings containing the warning messages, which is empty if no warnings were generated.
     */
    public List<String> getParseWarnings() {
        return parseWarnings;
    }

    /**
     * Returns true if the inventory parsing produced any warnings.
     *
     * @return true if the list of parse warnings is not empty, false otherwise.
     */
    public boolean hasParseWarnings() {
        return !parseWarnings.isEmpty();
    }

    /**
     * Returns a list of {@link Node} objects for all hosts belonging to the specified group.
     *
     * <p>The returned list contains one {@link Node} for each host in the group, with connection parameters (host,
     * user, port, and connection mode) and SSH password determined by the precedence of host-specific, group-specific,
     * and global variables defined in the inventory. If a host limit is set, only hosts within that limit are included
     * in the result.</p>
     *
     * @param groupName the name of the group to expand
     * @return a list of {@link Node} objects representing the hosts in the group
     * @throws IllegalStateException if the inventory has not been loaded
     */
    public List<Node> createNodesForGroup(String groupName) {
        if (inventory == null) {
            throw new IllegalStateException("Inventory not loaded. Call loadInventory() first.");
        }

        List<String> hosts = inventory.getHosts(groupName);
        List<Node> nodes = new ArrayList<>();

        // Get base variables
        Map<String, String> globalVars = inventory.getGlobalVars();
        Map<String, String> groupVars = inventory.getGroupVars(groupName);

        // Create nodes
        for (String hostname : hosts) {
            // Apply host limit if set
            if (hostLimit != null && !hostLimit.contains(hostname)) {
                continue;
            }
            // Merge vars with priority: host vars > group vars > global vars
            Map<String, String> effectiveVars = new HashMap<>(globalVars);
            effectiveVars.putAll(groupVars);
            effectiveVars.putAll(inventory.getHostVars(hostname));

            // Extract connection parameters for this host
            // Support both actoriac_* (preferred) and ansible_* (for compatibility) prefixes
            String actualHost = getVar(effectiveVars, "host", hostname);
            String user = getVar(effectiveVars, "user", System.getProperty("user.name"));
            int port = Integer.parseInt(getVar(effectiveVars, "port", "22"));

            // Check if local connection mode is requested
            String connection = getVar(effectiveVars, "connection", "ssh");
            boolean localMode = "local".equalsIgnoreCase(connection);

            // Create Node using actualHost (IP or DNS) for SSH connection
            Node node = new Node(actualHost, user, port, localMode, sshPassword);
            nodes.add(node);
        }

        return nodes;
    }

    /**
     * Returns a list containing a single {@link Node} configured for local execution on the current machine.
     *
     * @return a list containing one {@link Node} with the current system user, port 22, and local connection mode
     * enabled
     */
    public List<Node> createLocalNode() {
        Node localNode = new Node("localhost",
                                   System.getProperty("user.name"),
                                   22,
                                   true);  // localMode = true
        return List.of(localNode);
    }

    /**
     * Returns the parsed inventory data.
     *
     * @return the inventory object, or {@code null} if no inventory has been loaded
     */
    public InventoryParser.Inventory getInventory() {
        return inventory;
    }

    /**
     * Sets the SSH password to be used for all nodes created by this group.
     *
     * @param password the password string to be applied to every node
     */
    public void setSshPassword(String password) {
        this.sshPassword = password;
    }

    /** Returns the SSH password used for all nodes in this group, or {@code null} if no password has been set. */
    public String getSshPassword() {
        return sshPassword;
    }

    /**
     * Sets the host limit to restrict the nodes produced by {@link #createNodesForGroup(String)} to a specific subset.
     *
     * @param limitString a comma-separated list of hostnames to include, or a null or empty string to remove any limit
     */
    public void setHostLimit(String limitString) {
        if (limitString == null || limitString.trim().isEmpty()) {
            this.hostLimit = null;
        } else {
            this.hostLimit = new ArrayList<>();
            for (String host : limitString.split(",")) {
                String trimmed = host.trim();
                if (!trimmed.isEmpty()) {
                    this.hostLimit.add(trimmed);
                }
            }
        }
    }

    /**
     * Returns the list of hostnames that restrict the nodes created for a group.
     *
     * @return the list of limited hostnames, or {@code null} if no limit is set
     */
    public List<String> getHostLimit() {
        return hostLimit;
    }

    /**
     * Returns the value associated with the specified suffix, prioritizing the {@code actoriac_} prefix over the
     * {@code ansible_} prefix.
     *
     * @param vars the map of variables to search
     * @param suffix the name of the variable without its prefix
     * @param defaultValue the value to return if neither prefix is found
     * @return the value found for the {@code actoriac_} or {@code ansible_} prefixed key, or defaultValue if no match
     * exists
     */
    private String getVar(Map<String, String> vars, String suffix, String defaultValue) {
        // Check actoriac_* first (preferred)
        String actoriacKey = "actoriac_" + suffix;
        if (vars.containsKey(actoriacKey)) {
            return vars.get(actoriacKey);
        }

        // Fallback to ansible_* for compatibility
        String ansibleKey = "ansible_" + suffix;
        if (vars.containsKey(ansibleKey)) {
            return vars.get(ansibleKey);
        }

        return defaultValue;
    }

    /**
     * Returns a string containing the names of all groups defined in the loaded inventory.
     *
     * @return a string in the format {@code NodeGroup{groups=[group1, group2, ...]}}
     */
    @Override
    public String toString() {
        return String.format("NodeGroup{groups=%s}",
            inventory != null ? inventory.getAllGroups().keySet() : "[]");
    }
}
