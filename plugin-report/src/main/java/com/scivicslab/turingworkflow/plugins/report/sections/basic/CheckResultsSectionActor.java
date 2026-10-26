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
 * Generates a report section summarizing check results from the log database.
 *
 * <ul>
 * <li>`generate` returns an action result containing the section content as a string.</li>
 * <li>`getTitle` returns an action result containing the section title as a string, or an empty string if no title is
 * set.</li>
 * </ul>
 *
 * <p>The actor holds a database connection and a session ID retrieved from the {@code nodeGroup} actor.</p>
 *
 * <p>One instance may serve one workflow at a time.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class CheckResultsSectionActor extends IIActorRef<CheckResultsSection> {

    private static final Logger logger = Logger.getLogger(CheckResultsSectionActor.class.getName());

    /**
     * Initializes a new actor that produces a report section summarizing the success or failure of checks (labeled
     * `verify-*`) found in the log database.
     *
     * @param actorName the name to be used for this actor in the workflow
     * @param system the actor system used to retrieve the database connection and the session ID from the {@code
     * nodeGroup} actor
     */
    public CheckResultsSectionActor(String actorName, IIActorSystem system) {
        super(actorName, new CheckResultsSection(), system);
        initializeFromSystem();
    }

    /**
     * Sets the database connection and session ID in the underlying section object by retrieving them from the actor
     * system and the log store.
     *
     * @implNote It obtains a {@link java.sql.Connection} from {@link DistributedLogStore} and calls the {@code
     * nodeGroup} actor to retrieve a session ID via the {@code getSessionId} action.
     */
    private void initializeFromSystem() {
        // Get database connection from DistributedLogStore
        DistributedLogStore logStore = DistributedLogStore.getInstance();
        if (logStore != null) {
            Connection conn = logStore.getConnection();
            if (conn != null) {
                object.setConnection(conn);
                logger.fine("CheckResultsSectionActor: initialized database connection");
            }
        }

        // Get session ID from nodeGroup
        long sessionId = getSessionIdFromNodeGroup();
        if (sessionId >= 0) {
            object.setSessionId(sessionId);
            logger.fine("CheckResultsSectionActor: initialized sessionId=" + sessionId);
        }
    }

    /**
     * Returns the session ID of the current workflow by querying the {@code nodeGroup} actor.
     *
     * @return the session ID as a long, or -1 if the {@code nodeGroup} actor cannot be found, the system is
     * unavailable, or the returned ID is not a valid number.
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
                logger.warning("CheckResultsSectionActor: invalid sessionId: " + result.getResult());
            }
        }
        return -1;
    }

    /**
     * Returns a text block containing the results of checks labeled with {@code verify-}, showing the success or
     * failure of each node.
     *
     * @param args unused
     * @return an action result that is a success containing the generated section content as a string
     */
    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the title of the check results section.
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success and whose message holds the title string, or an empty string
     * if no title is available
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }
}
