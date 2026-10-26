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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses Ansible-style INI inventory files into a structured representation of hosts, groups, and variables.
 *
 * <p>The parser supports a subset of the Ansible INI format, including:
 * <ul>
 * <li>Groups: {@code [groupname]}</li>
 * <li>Group variables: {@code [groupname:vars]}</li>
 * <li>Global variables: {@code [all:vars]}</li>
 * <li>Host-specific variables: {@code hostname key=value}</li>
 * <li>Comments: lines starting with {@code #} or {@code ;}</li>
 * </ul>
 *
 * <p>It recognizes both Ansible-compatible variable names (prefixed with {@code ansible_}) and native Turing-workflow
 * names (prefixed with {@code actoriac_}) for host, user, port, and connection settings.
 *
 * <p>The parser generates warnings for unsupported features such as:
 * <ul>
 * <li>Children groups: {@code [group:children]}</li>
 * <li>Range patterns: {@code web[01:50]}</li>
 * <li>Privilege escalation: {@code ansible_become} or {@code ansible_become_user}</li>
 * <li>Python interpreter settings: {@code ansible_python_interpreter}</li>
 * </ul>
 *
 * <p>The {@code ParseResult} returned by the parser contains the resulting {@link Inventory} and a list of warning
 * messages that the caller should log.
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class InventoryParser {

    /** Matches strings containing Ansible range notation, such as {@code [01:50]} or {@code [a:z]}. */
    private static final Pattern RANGE_PATTERN = Pattern.compile(".*\\[[0-9a-zA-Z]+:[0-9a-zA-Z]+\\].*");

    /**
     * The suffixes of the {@code actoriac_} and {@code ansible_} variables that the parser recognizes as valid for
     * connection settings.
     */
    private static final Set<String> SUPPORTED_VAR_SUFFIXES = Set.of(
        "host", "user", "port", "connection"
    );

    /**
     * Contains the names of Ansible variables that the parser does not support and for which it generates a warning.
     */
    private static final Set<String> UNSUPPORTED_ANSIBLE_VARS = Set.of(
        "ansible_become",
        "ansible_become_user",
        "ansible_become_pass",
        "ansible_become_method",
        "ansible_become_flags",
        "ansible_python_interpreter",
        "ansible_shell_type",
        "ansible_shell_executable",
        "ansible_ssh_private_key_file",
        "ansible_ssh_common_args",
        "ansible_ssh_extra_args",
        "ansible_ssh_pipelining",
        "ansible_ssh_pass",
        "ansible_sudo",
        "ansible_sudo_pass"
    );

    /**
     * Parses an Ansible-style INI inventory file from the given input stream into an {@link ParseResult}.
     *
     * <p>The result contains the structured inventory and a list of warnings for unsupported features like `:children`
     * groups, range patterns, or specific Ansible variables. The caller is responsible for logging any warnings found
     * in the result.</p>
     *
     * @param input the input stream of the inventory file
     * @return a {@link ParseResult} containing the parsed inventory and any warnings
     * @throws IOException if an error occurs while reading the input stream
     */
    public static ParseResult parse(InputStream input) throws IOException {
        Inventory inventory = new Inventory();
        List<String> warnings = new ArrayList<>();
        Set<String> warnedVars = new HashSet<>();
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            String currentGroup = null;
            boolean inVarsSection = false;
            Map<String, String> currentVars = new HashMap<>();

            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                line = line.trim();

                // Skip empty lines and comments
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                // Check for group header
                if (line.startsWith("[") && line.endsWith("]")) {
                    String groupDeclaration = line.substring(1, line.length() - 1);

                    // Check for unsupported :children syntax
                    if (groupDeclaration.endsWith(":children")) {
                        warnings.add(String.format(
                            "Line %d: [%s] - ':children' groups are not supported. " +
                            "Please list hosts directly in each group instead.",
                            lineNumber, groupDeclaration));
                        currentGroup = null;
                        inVarsSection = false;
                        continue;
                    }

                    // Check if it's a vars section
                    if (groupDeclaration.endsWith(":vars")) {
                        inVarsSection = true;
                        currentGroup = groupDeclaration.substring(0, groupDeclaration.length() - 5);
                        currentVars = new HashMap<>();
                    } else {
                        inVarsSection = false;
                        currentGroup = groupDeclaration;
                        inventory.addGroup(currentGroup);
                    }
                    continue;
                }

                // Process content based on section type
                if (inVarsSection) {
                    // Parse variable assignment
                    int equalsIndex = line.indexOf('=');
                    if (equalsIndex > 0) {
                        String key = line.substring(0, equalsIndex).trim();
                        String value = line.substring(equalsIndex + 1).trim();

                        // Check for unsupported variables
                        checkUnsupportedVariable(key, lineNumber, warnedVars, warnings);

                        currentVars.put(key, value);

                        // Apply vars to group
                        if ("all".equals(currentGroup)) {
                            inventory.addGlobalVar(key, value);
                        } else if (currentGroup != null) {
                            inventory.addGroupVar(currentGroup, key, value);
                        }
                    }
                } else if (currentGroup != null) {
                    // Parse host line with optional variables
                    // Format: hostname [key=value key=value ...]
                    String[] tokens = line.split("\\s+");
                    String hostname = tokens[0];

                    // Check for unsupported range notation
                    if (RANGE_PATTERN.matcher(hostname).matches()) {
                        warnings.add(String.format(
                            "Line %d: '%s' - Range patterns like [01:50] are not supported. " +
                            "Please list each host individually.",
                            lineNumber, hostname));
                        continue;
                    }

                    inventory.addHost(currentGroup, hostname);

                    // Parse host-specific variables
                    for (int i = 1; i < tokens.length; i++) {
                        String token = tokens[i];
                        int equalsIndex = token.indexOf('=');
                        if (equalsIndex > 0) {
                            String key = token.substring(0, equalsIndex).trim();
                            String value = token.substring(equalsIndex + 1).trim();

                            // Check for unsupported variables
                            checkUnsupportedVariable(key, lineNumber, warnedVars, warnings);

                            inventory.addHostVar(hostname, key, value);
                        }
                    }
                }
            }
        }

        return new ParseResult(inventory, warnings);
    }

    /**
     * Adds a warning to the list if the variable is an unsupported Ansible feature or an unrecognized {@code
     * ansible_*} or {@code actoriac_*} variable.
     *
     * @param key the variable name
     * @param lineNumber the line number in the inventory file where the variable was found
     * @param warnedVars a set of variable names that have already been flagged to prevent duplicate warnings
     * @param warnings the list of warning messages to which a new warning may be added
     */
    private static void checkUnsupportedVariable(String key, int lineNumber,
                                                  Set<String> warnedVars, List<String> warnings) {
        // Check for known unsupported Ansible variables
        if (UNSUPPORTED_ANSIBLE_VARS.contains(key)) {
            if (!warnedVars.contains(key)) {
                warnedVars.add(key);
                String suggestion = getUnsupportedVarSuggestion(key);
                warnings.add(String.format(
                    "Line %d: '%s' is not supported. %s",
                    lineNumber, key, suggestion));
            }
            return;
        }

        // Check for ansible_* or actoriac_* variables that are not in the supported list
        if (key.startsWith("ansible_") || key.startsWith("actoriac_")) {
            String suffix = key.startsWith("ansible_")
                ? key.substring("ansible_".length())
                : key.substring("actoriac_".length());

            if (!SUPPORTED_VAR_SUFFIXES.contains(suffix) && !warnedVars.contains(key)) {
                warnedVars.add(key);
                warnings.add(String.format(
                    "Line %d: '%s' is not a recognized variable. " +
                    "Supported variables are: actoriac_host, actoriac_user, actoriac_port, actoriac_connection " +
                    "(or their ansible_* equivalents).",
                    lineNumber, key));
            }
        }
    }

    /**
     * Returns a human-readable suggestion for how to achieve the intended behavior of an unsupported Ansible variable.
     *
     * @param key the unsupported variable name
     */
    private static String getUnsupportedVarSuggestion(String key) {
        if (key.startsWith("ansible_become") || key.equals("ansible_sudo") || key.equals("ansible_sudo_pass")) {
            return "For privilege escalation, use the SUDO_PASSWORD environment variable and the executeSudoCommand() method in workflows.";
        }
        if (key.equals("ansible_python_interpreter")) {
            return "Commands are executed directly via SSH without Python. This variable is not needed.";
        }
        if (key.startsWith("ansible_ssh_")) {
            return "SSH configuration should be done via ~/.ssh/config or ssh-agent.";
        }
        return "This Ansible feature is not implemented.";
    }

    /**
     * Holds the parsed inventory and any warnings generated during the parsing of an Ansible INI file.
     *
     * <ul>
     * <li>{@link #getInventory()} returns the parsed {@link Inventory} object.</li>
     * <li>{@link #getWarnings()} returns a list of warning messages regarding unsupported features or syntax.</li>
     * <li>{@link #hasWarnings()} returns true if the list of warnings is not empty.</li>
     * </ul>
     *
     * @since 1.0
     */
    public static class ParseResult {
        private final Inventory inventory;
        private final List<String> warnings;

        /**
         * Creates a new ParseResult containing the parsed inventory and a list of warnings.
         *
         * @param inventory the parsed inventory
         * @param warnings the list of warning messages
         */
        public ParseResult(Inventory inventory, List<String> warnings) {
            this.inventory = inventory;
            this.warnings = warnings;
        }

        /**
         * Returns the parsed inventory structure containing all groups, hosts, and variables extracted from the input
         * file.
         *
         * @return the inventory object containing the parsed data
         */
        public Inventory getInventory() {
            return inventory;
        }

        /**
         * Returns a list of warning messages generated during the parsing of the inventory file.
         *
         * @return a list of strings, where each string describes a detected unsupported Ansible feature or syntax, or
         * an empty list if no warnings were generated.
         */
        public List<String> getWarnings() {
            return warnings;
        }

        /**
         * Returns {@code true} if the parsing process encountered any unsupported Ansible features or unrecognized
         * variables.
         *
         * @return {@code true} if the list of warnings is not empty, {@code false} otherwise
         */
        public boolean hasWarnings() {
            return !warnings.isEmpty();
        }
    }

    /**
     * Holds the structured data from a parsed Ansible INI inventory, including groups, hosts, and variables.
     *
     * <p>The data follows a precedence hierarchy where host-specific variables override group variables, which in turn
     * override global variables. The following methods allow access to the inventory structure:
     * <ul>
     * <li>{@code getHosts(String groupName)} returns the list of hostnames belonging to the specified group, or an
     * empty list if the group does not exist.</li>
     * <li>{@code getGlobalVars()} returns a map of all variables defined in the {@code [all:vars]} section.</li>
     * <li>{@code getGroupVars(String groupName)} returns a map of variables assigned to the specified group.</li>
     * <li>{@code getHostVars(String hostname)} returns a map of variables assigned to the specified host.</li>
     * <li>{@code getAllGroups()} returns a map of all group names to their respective lists of hostnames.</li>
     * </ul>
     *
     * <p>One instance may serve several workflows or threads at once.
     *
     * @since 1.0
     */
    public static class Inventory {
        /** Maps group names to their lists of hostnames. */
        private final Map<String, List<String>> groups = new HashMap<>();
        /** Holds the variables defined in the {@code [all:vars]} section of the inventory. */
        private final Map<String, String> globalVars = new HashMap<>();
        /** Holds variables defined for specific groups, keyed by the group name. */
        private final Map<String, Map<String, String>> groupVars = new HashMap<>();
        /** Holds variables specific to individual hosts, keyed by hostname. */
        private final Map<String, Map<String, String>> hostVars = new HashMap<>();

        /**
         * Adds a group to the inventory.
         *
         * @param groupName the name of the group to add
         */
        public void addGroup(String groupName) {
            groups.putIfAbsent(groupName, new ArrayList<>());
        }

        /**
         * Adds a hostname to the specified group.
         *
         * @param groupName the name of the group to which the host is added
         * @param hostname the hostname to add
         */
        public void addHost(String groupName, String hostname) {
            groups.computeIfAbsent(groupName, k -> new ArrayList<>()).add(hostname);
        }

        /**
         * Stores a variable that applies to all hosts in the inventory.
         *
         * @param key the name of the variable
         * @param value the value of the variable
         */
        public void addGlobalVar(String key, String value) {
            globalVars.put(key, value);
        }

        /**
         * Adds a variable to the variables section of a specific group.
         *
         * @param groupName the name of the group
         * @param key the variable name
         * @param value the variable value
         */
        public void addGroupVar(String groupName, String key, String value) {
            groupVars.computeIfAbsent(groupName, k -> new HashMap<>()).put(key, value);
        }

        /**
         * Associates a variable with a specific host.
         *
         * @param hostname the hostname to associate the variable with
         * @param key the variable name
         * @param value the variable value
         */
        public void addHostVar(String hostname, String key, String value) {
            hostVars.computeIfAbsent(hostname, k -> new HashMap<>()).put(key, value);
        }

        /**
         * Returns a list of hostnames that belong to the specified group.
         *
         * @param groupName the name of the group
         * @return a list of hostnames in the group, or an empty list if the group does not exist
         */
        public List<String> getHosts(String groupName) {
            return groups.getOrDefault(groupName, new ArrayList<>());
        }

        /**
         * Returns a map containing all variables defined in the {@code [all:vars]} section.
         *
         * @return a map of variable names to their values
         */
        public Map<String, String> getGlobalVars() {
            return new HashMap<>(globalVars);
        }

        /**
         * Returns a map containing the variables defined for the specified group.
         *
         * @param groupName the name of the group
         * @return a map of the group's variables, or an empty map if the group has no variables or does not exist
         */
        public Map<String, String> getGroupVars(String groupName) {
            return new HashMap<>(groupVars.getOrDefault(groupName, new HashMap<>()));
        }

        /**
         * Returns a map containing the variables assigned specifically to the given hostname.
         *
         * @param hostname the hostname to look up
         * @return a new map containing the host's variables, or an empty map if the host has no variables or does not
         * exist in the inventory
         */
        public Map<String, String> getHostVars(String hostname) {
            return new HashMap<>(hostVars.getOrDefault(hostname, new HashMap<>()));
        }

        /**
         * Returns a map where each key is a group name and the value is a list of hostnames belonging to that group.
         *
         * @return a new map containing all group names and their associated host lists
         */
        public Map<String, List<String>> getAllGroups() {
            return new HashMap<>(groups);
        }
    }
}
