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

package com.scivicslab.turingworkflow.plugins.report.sections.basic;

import com.scivicslab.turingworkflow.plugins.logdb.DistributedLogStore;
import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.sql.Connection;
import java.util.logging.Logger;

/**
 * Generates a text section for a report that summarizes GPU information, such as model names, VRAM, and driver
 * versions, extracted from system logs.
 *
 * <ul><li><code>generate</code> returns an action result containing the formatted GPU summary
 * text.</li><li><code>getTitle</code> returns an action result containing the section title.</li></ul>
 *
 * <p>This actor holds a database connection and a session ID retrieved from the {@code nodeGroup} actor to query the
 * logs.</p>
 *
 * <p>One instance may serve one workflow at a time.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class GpuSummarySectionActor extends IIActorRef<GpuSummarySection> {

    private static final Logger logger = Logger.getLogger(GpuSummarySectionActor.class.getName());

    /**
     * Creates a new actor that generates a text section summarizing GPU information extracted from logs.
     *
     * @param actorName the name to assign to this actor
     * @param system the actor system used to retrieve the session ID from the {@code nodeGroup} actor
     */
    public GpuSummarySectionActor(String actorName, IIActorSystem system) {
        super(actorName, new GpuSummarySection(), system);
        initializeFromSystem();
    }

    /**
     * Sets up the underlying {@link GpuSummarySection} by retrieving a database connection from the {@link
     * DistributedLogStore} and a session ID from the {@code nodeGroup} actor.
     */
    private void initializeFromSystem() {
        // Get database connection from DistributedLogStore
        DistributedLogStore logStore = DistributedLogStore.getInstance();
        if (logStore != null) {
            Connection conn = logStore.getConnection();
            if (conn != null) {
                object.setConnection(conn);
                logger.fine("GpuSummarySectionActor: initialized database connection");
            }
        }

        // Get session ID from nodeGroup
        long sessionId = getSessionIdFromNodeGroup();
        if (sessionId >= 0) {
            object.setSessionId(sessionId);
            logger.fine("GpuSummarySectionActor: initialized sessionId=" + sessionId);
        }
    }

    /**
     * Returns the session ID from the {@code nodeGroup} actor.
     *
     * Returns the session ID as a long, or -1 if the {@code nodeGroup} actor is not found, the {@code actorSystem} is
     * invalid, or the returned value is not a valid number.
     */
    private long getSessionIdFromNodeGroup() {
        if (actorSystem == null || !(actorSystem instanceof IIActorSystem)) {
            return -1;
        }
        IIActorSystem iiSystem = (IIActorSystem) actorSystem;

        IIActorRef<?> nodeGroup = iiSystem.getIIActor("nodeGroup");
        if (nodeGroup == null) {
            return -1;
        }

        ActionResult result = nodeGroup.callByActionName("getSessionId", "");
        if (result.isSuccess()) {
            try {
                return Long.parseLong(result.getResult());
            } catch (NumberFormatException e) {
                logger.warning("GpuSummarySectionActor: invalid sessionId: " + result.getResult());
            }
        }
        return -1;
    }

    /**
     * Returns an action result containing the GPU summary section content.
     *
     * <p>The result is a success with the generated text as the message. The argument is unused.</p>
     *
     * @param args unused
     */
    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the title of the GPU summary section.
     *
     * <p>The result is a success containing the section title as a string, or an empty string if no title is set.</p>
     *
     * @param args unused
     * @return an action result containing the section title string
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }
}
