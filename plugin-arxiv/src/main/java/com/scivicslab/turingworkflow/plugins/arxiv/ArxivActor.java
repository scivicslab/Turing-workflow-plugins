package com.scivicslab.turingworkflow.plugins.arxiv;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Fetches bibliographic data, preprints, and PDF URLs from arXiv.
 *
 * <ul>
 * <li>{@code setEmail} sets the contact email address included in the user agent for requests.</li>
 * <li>{@code searchPapers} returns up to 10 relevant papers for a given query.</li>
 * <li>{@code searchPapersTopK} returns a specified number of papers, sorted by relevance, newest, or updated.</li>
 * <li>{@code getPaper} returns a single paper's details including its abstract.</li>
 * <li>{@code getPdfUrl} returns the URL for the paper's PDF.</li>
 * <li>{@code searchByAuthor} returns papers by a specific author, sorted by newest.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at once.</p>
 */
public class ArxivActor extends IIActorRef<ArxivClient> {

    /**
     * Creates a new actor for querying the arXiv API under a specific name.
     *
     * @param name the name used to call this actor in a workflow
     * @param system the actor system this actor belongs to
     */
    public ArxivActor(String name, IIActorSystem system) {
        super(name, new ArxivClient(), system);
    }

    private ArxivClient pojo() {
        return object;
    }

    /**
     * Carries the contact email address to be included in the user agent for arXiv requests.
     *
     * @param email the contact email address to be sent with each request
     */
    public record EmailArgs(@NotNull String email) {}

    /**
     * Arguments for a search on arXiv.
     *
     * @param query the search terms; arXiv's own field prefixes (such as {@code cat:} or {@code abs:}) are passed
     * through as written
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * Arguments for a search that specifies the query, the number of papers to return, and the sort order.
     *
     * @param query the search terms; arXiv's own field prefixes are passed through
     * @param maxResults the maximum number of papers to return, or 10 if absent
     * @param sort the sort order, either {@code relevance}, {@code newest}, or {@code updated}; {@code relevance} if
     * absent
     */
    public record TopPapersArgs(@NotNull String query, Integer maxResults, String sort) {}

    /**
     * The arXiv identifier for a paper.
     *
     * @param id the identifier of the paper
     */
    public record PaperArgs(@NotNull String id) {}

    /**
     * Sets the contact email address to be included in the user agent for all subsequent requests.
     *
     * @param args the object containing the contact email address
     */
    @Action(value = "setEmail", argsType = EmailArgs.class)
    public ActionResult setEmail(EmailArgs args) {
        return pojo().setEmail(args.email());
    }

    /**
     * Returns up to 10 papers ordered by relevance for a given query.
     *
     * <p>An action result is a success if the search completed; otherwise, the message holds the error or the reason
     * for refusal.
     *
     * @param args the search terms; arXiv's own field prefixes (such as {@code cat:} or {@code abs:}) are passed
     * through as written
     *
     * @return an action result that is a success and contains the list of papers as a message, or a failure result</p>
     */
    @Action(value = "searchPapers", argsType = QueryArgs.class)
    public ActionResult searchPapers(QueryArgs args) {
        return pojo().searchPapers(args.query());
    }

    /**
     * Returns an action result containing a list of papers found on arXiv.
     *
     * @param args the object containing the search terms, the maximum number of papers to return (ten when absent),
     * and the sort order, which is {@code relevance}, {@code newest}, or {@code updated} (relevance when absent)
     *
     * @return an action result that is a success if the search completed, with the list of papers as the message;
     * otherwise, a failure result containing the error or the reason for refusal
     */
    @Action(value = "searchPapersTopK", argsType = TopPapersArgs.class)
    public ActionResult searchPapersTopK(TopPapersArgs args) {
        return pojo().searchPapersTopK(args.query(), args.maxResults(), args.sort());
    }

    /**
     * Returns a single paper's bibliographic data and abstract for the given ID.
     *
     * An action result is a success if the paper was found, with the paper's data as the message; otherwise, it is a
     * failure.
     *
     * @param args the object containing the arXiv identifier, which may be provided with or without its version,
     * prefix, or URL
     *
     * @return an action result containing the paper's bibliographic data and abstract on success, or a failure message
     */
    @Action(value = "getPaper", argsType = PaperArgs.class)
    public ActionResult getPaper(PaperArgs args) {
        return pojo().getPaper(args.id());
    }

    /**
     * Returns the URL for the paper's PDF.
     *
     * An action result is a success if the paper is found, with the URL as the message; otherwise, it is a failure
     * result.
     *
     * @param args the object containing the paper identifier, with or without its version, prefix or URL
     * @return an action result that is a success containing the PDF URL, or a failure result if the paper cannot be
     * found or the request fails
     */
    @Action(value = "getPdfUrl", argsType = PaperArgs.class)
    public ActionResult getPdfUrl(PaperArgs args) {
        return pojo().getPdfUrl(args.id());
    }

    /**
     * Returns a list of papers by a specific author, sorted by publication date from newest to oldest.
     *
     * An action result is a success if the search completed; otherwise, the message holds the error or the reason for
     * refusal.
     *
     * @param args the object containing the author name as the query string
     */
    @Action(value = "searchByAuthor", argsType = QueryArgs.class)
    public ActionResult searchByAuthor(QueryArgs args) {
        return pojo().searchByAuthor(args.query());
    }
}
