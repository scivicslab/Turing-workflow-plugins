package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Performs web searches via a {@code WebSearcher} and returns search results or lists of URLs as action results.
 *
 * <ul>
 * <li>search: returns an action result containing search results for the given query.</li>
 * <li>searchUrls: returns an action result containing only URLs for the given query.</li>
 * </ul>
 *
 * <p>This actor is stateless; the underlying {@code WebSearcher} holds the state. One instance may serve several
 * workflows at the same time.</p>
 */
public class WebSearchActor extends IIActorRef<WebSearcher> {

    /**
     * Registers a new actor named {@code name} in the {@code system} that performs web searches using a {@link
     * WebSearcher}.
     *
     * @param name the name to register this actor under in the system
     * @param system the actor system where this actor will reside
     */
    public WebSearchActor(String name, IIActorSystem system) {
        super(name, new WebSearcher(), system);
    }

    private WebSearcher pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * Holds the search terms for a web search.
     *
     * @param query the search terms to be used for the query
     */
    public record QueryArgs(@NotNull String query) {}

    @Action(value = "search", argsType = QueryArgs.class)
    public ActionResult search(QueryArgs args) {
        return pojo().search(args.query());
    }

    /**
     * Returns an action result containing a list of URLs found for the given search terms.
     *
     * @param args the search terms to be used for the query
     * @return a success result with a message containing the URLs, or a failure result if the search could not be
     * completed
     */
    @Action(value = "searchUrls", argsType = QueryArgs.class)
    public ActionResult searchUrls(QueryArgs args) {
        return pojo().searchUrls(args.query());
    }

}