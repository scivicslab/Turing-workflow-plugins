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

package com.scivicslab.turingworkflow.plugins.logoutput;

import com.github.ricksbrown.cowsay.Cowsay;
import com.scivicslab.pojoactor.core.accumulator.StreamingAccumulator;

/**
 * StreamingAccumulator with cowsay display support for Turing-workflow.
 *
 * <p>This accumulator extends {@link StreamingAccumulator} to add cowsay
 * ASCII art visualization for workflow step transitions.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class WorkflowStreamingAccumulator extends StreamingAccumulator {

    /** The cowfile name used for cowsay rendering, or {@code null} for the default cow. */
    private String cowfile = null;

    /**
     * Constructs a new {@code WorkflowStreamingAccumulator} with the default cow character.
     */
    public WorkflowStreamingAccumulator() {
        super();
    }

    /**
     * Sets the cowfile name for cowsay output.
     *
     * @param cowfile the cowfile name (e.g., "tux", "dragon"), or null for default cow
     */
    public void setCowfile(String cowfile) {
        this.cowfile = cowfile;
    }

    /**
     * Returns the current cowfile name.
     *
     * @return the cowfile name, or {@code null} if the default cow is used
     */
    public String getCowfile() {
        return cowfile;
    }

    /**
     * Renders a workflow step as cowsay ASCII art and returns the result.
     *
     * @param workflowName the name of the workflow
     * @param stepYaml the YAML representation of the step
     * @return the rendered cowsay ASCII art string
     */
    public String renderCowsay(String workflowName, String stepYaml) {
        String displayText = "[" + workflowName + "]\n" + stepYaml;
        String[] cowsayArgs;
        if (cowfile != null && !cowfile.isBlank()) {
            cowsayArgs = new String[] { "-f", cowfile, displayText };
        } else {
            cowsayArgs = new String[] { displayText };
        }
        return Cowsay.say(cowsayArgs);
    }

    /**
     * Lists all available cowfile names.
     *
     * @return array of available cowfile names
     */
    public static String[] listCowfiles() {
        String[] listArgs = { "-l" };
        String cowList = Cowsay.say(listArgs);
        return cowList.trim().split("\\s+");
    }
}
