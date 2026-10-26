package com.scivicslab.turingworkflow.plugins.openalex;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Provides access to the OpenAlex database for searching papers, authors, and retrieving metadata or PDF URLs.
 *
 * <ul><li >{@code setEmail}: sets the email address to be included in the user agent for requests</li><li >{@code
 * searchWorks}: searches for works matching a query and returns a list</li><li >{@code searchWorksTopK}: searches for
 * works with a specific count and sort order</li><li >{@code getWork}: returns a single work and its abstract by its
 * identifier or DOI</li><li >{@code getPdfUrl}: returns the URL for the work's PDF</li><li >{@code searchAuthors}:
 * searches for authors matching a query</li></ul>
 *
 * <p>The actor itself is stateless; all state is held within the underlying {@link OpenAlexClient}. One instance may
 * serve several workflows at once.</p>
 */
public class OpenAlexActor extends IIActorRef<OpenAlexClient> {

    /**
     * Initializes a new OpenAlex actor that performs bibliographic searches and paper lookups via the OpenAlex API.
     *
     * @param name the name used to call this actor in a workflow
     * @param system the actor system this actor belongs to
     */
    public OpenAlexActor(String name, IIActorSystem system) {
        super(name, new OpenAlexClient(), system);
    }

    private OpenAlexClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * The email address that OpenAlex asks callers to include with each request.
     *
     * @param email the caller's mail address
     */
    public record EmailArgs(@NotNull String email) {}

    /**
     * The search terms to use for an OpenAlex query.
     *
     * @param query the search terms
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * Arguments for a search that specifies the query, the number of papers to return, and the sort order.
     *
     * @param query the search terms
     * @param perPage the number of papers to return
     * @param sort the ordering, such as {@code citations}, {@code relevance}, {@code newest}, or an OpenAlex sort
     * string like {@code publication_date:desc}
     */
    public record TopWorksArgs(@NotNull String query, Integer perPage, String sort) {}

    /**
     * The OpenAlex identifier or DOI of the paper to look up.
     *
     * @param id the paper's OpenAlex identifier or its DOI
     */
    public record WorkArgs(@NotNull String id) {}

    @Action(value = "setEmail", argsType = EmailArgs.class)
    public ActionResult setEmail(EmailArgs args) {
        return pojo().setEmail(args.email());
    }

    /**
     * Searches OpenAlex for works matching the query.
     *
     * @param args the search terms
     *
     * @return an action result that is a success if the search completed, with the results in its message, and is not
     * a success otherwise
     */
    @Action(value = "searchWorks", argsType = QueryArgs.class)
    public ActionResult searchWorks(QueryArgs args) {
        return pojo().searchWorks(args.query());
    }

    /**
     * Searches OpenAlex for works with a specified number of results and an ordering.
     *
     * @param args the arguments containing the search terms, the number of papers to return, and the sort order
     * @return an action result that is a success and contains the list of works if the search succeeded, or a failure
     * message if it did not
     */
    @Action(value = "searchWorksTopK", argsType = TopWorksArgs.class)
    public ActionResult searchWorksTopK(TopWorksArgs args) {
        return pojo().searchWorksTopK(args.query(), args.perPage(), args.sort());
    }

    /**
     * Returns a single paper's details, including its abstract, from OpenAlex.
     *
     * @param args the argument object containing the paper's OpenAlex identifier or its DOI
     * @return an action result that is a success and contains the paper's details, or a failure if the paper is not
     * found or the request fails
     */
    @Action(value = "getWork", argsType = WorkArgs.class)
    public ActionResult getWork(WorkArgs args) {
        return pojo().getWork(args.id());
    }

    /**
     * Returns the URL of the paper's PDF if an open access version is available.
     *
     * @param args the paper's OpenAlex identifier or its DOI
     * @return an action result that is a success and contains the URL if found, or is not a success if no PDF URL is
     * available
     */
    @Action(value = "getPdfUrl", argsType = WorkArgs.class)
    public ActionResult getPdfUrl(WorkArgs args) {
        return pojo().getPdfUrl(args.id());
    }

    /**
     * Searches OpenAlex for authors matching the query and returns a list of results.
     *
     * @param args a record containing the search terms in {@code query()}
     *
     * @return an action result that is a success containing the list of authors, or a failure if the search could not
     * be completed
     */
    @Action(value = "searchAuthors", argsType = QueryArgs.class)
    public ActionResult searchAuthors(QueryArgs args) {
        return pojo().searchAuthors(args.query());
    }

}