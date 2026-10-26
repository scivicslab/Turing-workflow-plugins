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

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import java.sql.Connection;
import java.util.logging.Logger;

/**
 * Generates a text section showing the chronological history of state transitions for a specific actor or group of
 * actors.
 *
 * <ul><li>{@code generate} returns an action result containing the formatted transition history text.</li><li>{@code
 * getTitle} returns an action result containing the section title.</li><li>{@code setTargetActor} sets the name of the
 * actor whose transitions are tracked.</li><li>{@code setIncludeChildren} sets whether the transitions of the target
 * actor's children are also included in the report.</li></ul>
 *
 * <p>The actor name can specify the target and whether to include children using a colon-separated format, such as
 * {@code "trans:node-server1:children"}.</p>
 *
 * <p>One instance may serve several workflows at once.</p>
 *
 * <p>Example workflow step:</p>
 * <pre>{@code
 * actor: transition-history-actor
 * method: generate
 * }</pre>
 *
 * <p>If the database connection cannot be established or the session ID cannot be retrieved from the {@code nodeGroup}
 * actor, the section may be empty or fail to show recent history.</p>
 *
 * @author devteam@scivicslab.com
 * @since 2.16.0
 */
public class TransitionHistorySectionActor extends IIActorRef<TransitionHistorySection> {

    private static final Logger logger = Logger.getLogger(TransitionHistorySectionActor.class.getName());

    /**
     * Creates an actor that produces a text section showing the chronological history of state transitions for a
     * specific actor or group of actors.
     *
     * @param actorName the name of the actor to track, which may include a prefix to specify a target actor or the
     * inclusion of children (e.g., "trans:node-server1" or "trans:nodeGroup:children")
     *
     * @param system the actor system used to retrieve the session ID from the {@code nodeGroup} actor
     */
    public TransitionHistorySectionActor(String actorName, IIActorSystem system) {
        super(actorName, new TransitionHistorySection(), system);
        parseActorName(actorName);
        initializeFromSystem();
    }

    /**
     * Sets the target actor and whether to include its children based on the actor name.
     *
     * <p>If the actor name is {@code null} or does not contain a colon, the target actor is set to {@code nodeGroup}.
     * If the name follows the format {@code prefix:targetActor} or {@code prefix:targetActor:children}, the target
     * actor is set to the second part, and the inclusion of children is enabled only if the third part is {@code
     * children}.</p>
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
     * Sets the database connection and the session ID for the underlying section.
     *
     * <p>It retrieves a {@link java.sql.Connection} from the {@link DistributedLogStore} and the session ID by calling
     * {@code getSessionId} on the {@code nodeGroup} actor. If the connection or the session ID cannot be retrieved,
     * they are not set.</p>
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
     * Returns the session ID of the {@code nodeGroup} actor, or -1 if the ID cannot be retrieved.
     *
     * <p>A failure to find the {@code nodeGroup} actor or a failure in the {@code getSessionId} action results in -1.
     * If the returned value is not a valid long, -1 is returned.</p>
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
    /**
     * Arguments for setting the target actor for the transition history section.
     *
     * @param actor the name of the actor whose transitions are to be shown
     */
    public record TargetActorArgs(@NotNull String actor) {}

    /**
     * Indicates whether the transitions of the target actor's children should be included in the report.
     *
     * @param includeChildren true to include the transitions of child actors, false otherwise.
     */
    public record IncludeChildrenArgs(@NotNull Boolean includeChildren) {}

    @Action("generate")
    public ActionResult generate(String args) {
        String content = object.generate();
        return new ActionResult(true, content);
    }

    /**
     * Returns the title of the transition history section.
     *
     * @param args unused
     * @return an {@link ActionResult} that is a success and whose message holds the title string, or an empty string
     * if the title is null
     */
    @Action("getTitle")
    public ActionResult getTitle(String args) {
        String title = object.getTitle();
        return new ActionResult(true, title != null ? title : "");
    }

    /**
     * Sets the target actor name for the transition history section.
     *
     * Returns an action result that is a success with the message "Target actor set".
     *
     * If the provided actor name is empty, the target actor name remains unchanged.
     *
     * <pre>{@code
     * actor: transition-history-actor
     * method: setTargetActor
     * arguments:
     *   actor: "node-server1"
     * }</pre>
     *
     * @param args a record containing the target actor name to be set
     */
    @Action(value = "setTargetActor", argsType = TargetActorArgs.class)
    public ActionResult setTargetActor(TargetActorArgs args) {
        if (!args.actor().isEmpty()) {
            object.setTargetActorName(args.actor().trim());
        }
        return new ActionResult(true, "Target actor set");
    }

    /**
     * Sets whether the transitions of the child actors are included in the section.
     *
     * @param args a record containing the boolean value to set for includeChildren
     *
     * @return an action result that is a success with a message stating the new inclusion status
     */
    @Action(value = "setIncludeChildren", argsType = IncludeChildrenArgs.class)
    public ActionResult setIncludeChildren(IncludeChildrenArgs args) {
        boolean include = args.includeChildren();
        object.setIncludeChildren(include);
        return new ActionResult(true, "Include children: " + include);
    }
}
