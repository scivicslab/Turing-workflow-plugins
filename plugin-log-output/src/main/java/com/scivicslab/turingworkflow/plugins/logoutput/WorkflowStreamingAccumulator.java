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
 * Provides ASCII art visualizations of workflow step transitions using cowsay.
 *
 * <p><ul><li>{@code renderCowsay} returns a string containing the workflow name and step YAML rendered as cowsay ASCII
 * art.</li><li>{@code listCowfiles} returns an array of available cowfile names.</li></ul></p>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <pre>{@code
 * WorkflowStreamingAccumulator acc = new WorkflowStreamingAccumulator();
 * acc.setCowfile("tux");
 * String art = acc.renderCowsay("my-workflow", "step: deploy");
 * }</pre>
 *
 * @author devteam@scivicslab.com
 * @since 1.0
 */
public class WorkflowStreamingAccumulator extends StreamingAccumulator {

    /** The name of the cowfile used for cowsay ASCII art rendering, or {@code null} to use the default cow. */
    private String cowfile = null;

    /**
     * Initializes a new {@code WorkflowStreamingAccumulator} that uses the default cow character for ASCII art
     * rendering.
     */
    public WorkflowStreamingAccumulator() {
        super();
    }

    /**
     * Sets the name of the cow character used for ASCII art rendering.
     *
     * @param cowfile the name of the cowfile to use, such as "tux" or "dragon", or {@code null} to use the default cow
     */
    public void setCowfile(String cowfile) {
        this.cowfile = cowfile;
    }

    /** Returns the name of the cowfile used for ASCII art rendering, or {@code null} if the default cow is used. */
    public String getCowfile() {
        return cowfile;
    }

    /**
     * Returns a string containing ASCII art that displays the workflow name and step details in a speech bubble.
     *
     * @param workflowName the name of the workflow to display
     * @param stepYaml the YAML representation of the step to display
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

    /** Returns an array of strings containing the names of all available cowfiles available for rendering. */
    public static String[] listCowfiles() {
        String[] listArgs = { "-l" };
        String cowList = Cowsay.say(listArgs);
        return cowList.trim().split("\\s+");
    }
}
