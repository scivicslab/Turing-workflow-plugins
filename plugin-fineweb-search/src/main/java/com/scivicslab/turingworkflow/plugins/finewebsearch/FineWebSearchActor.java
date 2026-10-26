package com.scivicslab.turingworkflow.plugins.finewebsearch;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Searches the FineWeb index and configures the target server URL.
 *
 * <ul><li>{@code setUrl}: Sets the address of the FineWeb search server.</li><li>{@code search}: Searches the index
 * for a query and returns the results.</li><li>{@code searchTopK}: Searches the index for a query and returns up to a
 * specified number of results.</li><li>{@code health}: Checks the connectivity and status of the FineWeb search
 * service.</li></ul>
 *
 * <p>One instance may serve several workflows at once.</p>
 *
 * <p>If the underlying client is not initialized or becomes invalid, actions will fail with a non-success result.</p>
 */
public class FineWebSearchActor extends IIActorRef<FineWebSearchClient> {

    /**
     * Creates a new actor named name in the specified system to perform searches against the FineWeb index.
     *
     * @param name the name used to reference this actor in a workflow
     * @param system the actor system this actor belongs to
     */
    public FineWebSearchActor(String name, IIActorSystem system) {
        super(name, new FineWebSearchClient(), system);
    }

    private FineWebSearchClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * Holds the address of the FineWeb search server.
     *
     * @param url the server's address
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * The search query to be sent to the FineWeb index.
     *
     * @param query the text to search for
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * Arguments for a search that specifies a limit on the number of results.
     *
     * @param query the text to search for
     * @param topK the maximum number of results to return; if null, ten results are returned
     */
    public record TopKArgs(@NotNull String query, Integer topK) {}

    @Action(value = "setUrl", argsType = UrlArgs.class)
    public ActionResult setUrl(UrlArgs args) {
        return pojo().setUrl(args.url());
    }

    /**
     * Returns an action result containing the search results for the specified query.
     *
     * @param args the text to search for in the FineWeb index
     * @return a success result with the search results as a message, or a failure result if the search could not be
     * completed
     */
    @Action(value = "search", argsType = QueryArgs.class)
    public ActionResult search(QueryArgs args) {
        return pojo().search(args.query());
    }

    /**
     * Returns an action result containing up to the specified number of search results for the query.
     *
     * @param args the query to search for and the maximum number of results to return, which is ten if topK is null
     * @return a success result containing the results as a message, or a failure result if the search could not be
     * completed
     */
    @Action(value = "searchTopK", argsType = TopKArgs.class)
    public ActionResult searchTopK(TopKArgs args) {
        return pojo().searchTopK(args.query(), args.topK());
    }

    /**
     * Returns an action result indicating whether the FineWeb search server is reachable.
     *
     * @param args the argument from the workflow, which is ignored
     * @return a success result with a message describing the status if the server is reachable, or a failure result
     * otherwise
     */
    @Action("health")
    public ActionResult health(String args) {
        return pojo().health();
    }

}