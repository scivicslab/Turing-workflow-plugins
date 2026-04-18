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
import com.scivicslab.pojoactor.core.Action;
import com.scivicslab.pojoactor.core.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.sql.Connection;
import java.util.logging.Logger;

/**
 * Actor wrapper for {@link TransitionHistorySection}.
 *
 * <p>Exposes the POJO's methods as actions via {@code @Action} annotations.
 * Handles database connection and session ID retrieval from the system.</p>
 *
 * <p>The actor name encodes target actor and options:</p>
 * <ul>
 *   <li>"transitions" - target=nodeGroup, includeChildren=false</li>
 *   <li>"trans:node-server1" - target=node-server1, includeChildren=false</li>
 *   <li>"trans:nodeGroup:children" - target=nodeGroup, includeChildren=true</li>
 * </ul>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class TransitionHistorySectionActor extends IIActorRef<TransitionHistorySection> {

    private static final Logger logger = Logger.getLogger(TransitionHistorySectionActor.class.getName());

    /**
     * Constructs the actor with a new POJO instance.
     *
     * @param actorName the actor name (may encode target actor)
     * @param system the actor system
     */
    public TransitionHistorySectionActor(String actorName, IIActorSystem system) {
        super(actorName, new TransitionHistorySection(), system);
        parseActorName(actorName);
        initializeFromSystem();
    }

    /**
     * Parses the actor name to extract target actor and options.
     */
    private void parseActorName(String actorName) {
        if (actorName == null) {
            object.setTargetActorName("nodeGroup");
            return;
        }

        // Format: "prefix:targetActor" or "prefix:targetActor:children"
        String[] parts = actorName.split(":", 3);
        if (parts.length >= 2) {
            object.setTargetActorName(parts[1]);
            if (parts.length >= 3 && "children".equals(parts[2])) {
                object.setIncludeChildren(true);
            }
        } else {
            // No ":" - default to nodeGroup
            object.setTargetActorName("nodeGroup");
        }
    }

    /**
     * Initializes the POJO with database connection and session ID.
     */
    private void initializeFromSystem() {
        // Get database connection from DistributedLogStore
        DistributedLogStore logStore = DistributedLogStore.getInstance();
        if (logStore != null) {
            Connection conn = logStore.getConnection();
            if (conn != null) {
                object.setConnection(conn);
                logger.fine("TransitionHistorySectionActor: initialized database connection");
            }
        }

        // Get session ID from nodeGroup
        long sessionId = getSessionIdFromNodeGroup();
        if (sessionId >= 0) {
            object.setSessionId(sessionId);
            logger.fine("TransitionHistorySectionActor: initialized sessionId=" + sessionId);
        }
    }

    /**
     * Retrieves session ID from nodeGroup actor.
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
                logger.warning("TransitionHistorySectionActor: invalid sessionId: " + result.getResult());
            }
        }
        return -1;
    }

    /**
     * Generates the transition history section content.
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} with the generated section content
     */
    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the section title.
     *
     * <p><strong>Expected args:</strong> unused (may be empty or null).</p>
     *
     * @param args unused
     * @return an {@link ActionResult} with the title string (empty if no title)
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }

    /**
     * Sets the target actor name dynamically.
     *
     * @param args the target actor name
     * @return action result
     */
    @Action("setTargetActor")
    public ActionResult setTargetActor(String args) {
        if (args != null && !args.isEmpty()) {
            object.setTargetActorName(args.trim());
        }
        return new ActionResult(true, "Target actor set");
    }

    /**
     * Sets whether to include children.
     *
     * @param args "true" or "false"
     * @return action result
     */
    @Action("setIncludeChildren")
    public ActionResult setIncludeChildren(String args) {
        boolean include = "true".equalsIgnoreCase(args);
        object.setIncludeChildren(include);
        return new ActionResult(true, "Include children: " + include);
    }
}
