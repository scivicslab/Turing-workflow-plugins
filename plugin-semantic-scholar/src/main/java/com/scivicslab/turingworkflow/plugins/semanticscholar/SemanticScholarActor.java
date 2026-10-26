package com.scivicslab.turingworkflow.plugins.semanticscholar;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Fetches bibliographic data and citation graphs from the Semantic Scholar Graph API.
 *
 * <ul>
 * <li>{@code setApiKey} sets the API key used for requests.</li>
 * <li>{@code searchPapers} returns up to 10 papers matching a query.</li>
 * <li>{@code searchPapersTopK} returns a specified number of papers matching a query, optionally filtered by year or
 * field of study.</li>
 * <li>{@code getPaper} returns a single paper with its abstract and TLDR.</li>
 * <li>{@code getPdfUrl} returns the URL for an open access PDF of the paper.</li>
 * <li>{@code getCitations} returns a list of papers that cite the given paper.</li>
 * <li>{@code getReferences} returns a list of papers that the given paper cites.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows at once. The underlying {@link SemanticScholarClient} holds the state,
 * including the API key and the rate-limiting timer shared across the JVM.</p>
 */
public class SemanticScholarActor extends IIActorRef<SemanticScholarClient> {

    /**
     * Creates a new {@code SemanticScholarActor} with the given name and system.
     *
     * @param name the name to register this actor under
     * @param system the actor system this actor belongs to
     */
    public SemanticScholarActor(String name, IIActorSystem system) {
        super(name, new SemanticScholarClient(), system);
    }

    private SemanticScholarClient pojo() {
        return object;
    }

    /**
     * The API key to be sent as {@code x-api-key}.
     *
     * @param apiKey the key issued by Semantic Scholar
     */
    public record ApiKeyArgs(@NotNull String apiKey) {}

    /**
     * The search terms for a paper search.
     *
     * @param query the search terms
     */
    public record QueryArgs(@NotNull String query) {}

    /**
     * Arguments for searching papers with specific constraints on quantity, time, and subject.
     *
     * @param query the search terms
     * @param limit the maximum number of papers to return; ten when absent
     * @param year a single year or a range such as {@code 2020-2024}; all years when absent
     * @param fields a field of study such as {@code Computer Science}; all fields when absent
     */
    public record TopPapersArgs(@NotNull String query, Integer limit, String year, String fields) {}

    /**
     * The identifier for a paper to look up.
     *
     * @param id a Semantic Scholar id, a DOI, or an arXiv id
     */
    public record PaperArgs(@NotNull String id) {}

    /**
     * Arguments for retrieving a paper's citations or references.
     *
     * @param id the paper's Semantic Scholar id, DOI, or arXiv id
     * @param limit the number of papers to return; ten when absent
     */
    public record RelatedArgs(@NotNull String id, Integer limit) {}

    /**
     * Sets the API key to be used in the {@code x-api-key} header for subsequent requests.
     *
     * @param args the key Semantic Scholar issued
     */
    @Action(value = "setApiKey", argsType = ApiKeyArgs.class)
    public ActionResult setApiKey(ApiKeyArgs args) {
        return pojo().setApiKey(args.apiKey());
    }

    /**
     * Searches the Semantic Scholar database for papers matching the query and returns the results.
     *
     * @param args the search terms to use for the query
     * @return an action result that is a success and contains the matching papers, or a failure if the search could
     * not be completed
     */
    @Action(value = "searchPapers", argsType = QueryArgs.class)
    public ActionResult searchPapers(QueryArgs args) {
        return pojo().searchPapers(args.query());
    }

    /**
     * Searches for papers using a query and narrows the results by count, year, or field. Returns an action result
     * containing a list of papers matching the criteria.
     *
     * <p>An action result is a success if papers are found; otherwise, the message holds the error or reason for
     * failure.</p>
     *
     * @param args the search terms, the maximum number of papers to return (ten when absent), a year or a range such
     * as {@code 2020-2024} (every year when absent), and a field of study such as {@code Computer Science} (every
     * field when absent)
     *
     * @return an action result containing the list of papers or an error message
     */
    @Action(value = "searchPapersTopK", argsType = TopPapersArgs.class)
    public ActionResult searchPapersTopK(TopPapersArgs args) {
        return pojo().searchPapersTopK(args.query(), args.limit(), args.year(), args.fields());
    }

    /**
     * Returns a single paper's details, including its abstract and TLDR, from the Semantic Scholar database.
     *
     * @param args the paper to look up, using a Semantic Scholar id, a DOI, or an arXiv id
     * @return an action result that is a success and contains the paper's details if found, or a failure if the paper
     * could not be retrieved or the request was refused
     */
    @Action(value = "getPaper", argsType = PaperArgs.class)
    public ActionResult getPaper(PaperArgs args) {
        return pojo().getPaper(args.id());
    }

    /**
     * Returns the URL of an open access PDF for the specified paper.
     *
     * @param args a record containing the paper ID (a Semantic Scholar id, a DOI, or an arXiv id)
     * @return an action result that is a success with the URL as the message, or a failure if no open access PDF is
     * available
     */
    @Action(value = "getPdfUrl", argsType = PaperArgs.class)
    public ActionResult getPdfUrl(PaperArgs args) {
        return pojo().getPdfUrl(args.id());
    }

    /**
     * Returns a list of papers that cite the specified paper.
     *
     * @param args the arguments containing the paper id and the maximum number of results to return
     * @return an action result that is a success and contains the list of citing papers, or is not a success if the
     * paper could not be found or the request failed
     */
    @Action(value = "getCitations", argsType = RelatedArgs.class)
    public ActionResult getCitations(RelatedArgs args) {
        return pojo().getCitations(args.id(), args.limit());
    }

    /**
     * Returns an action result containing a list of papers that the specified paper cites.
     *
     * @param args the arguments containing the paper id and the limit (number of papers to return; ten when absent)
     * @return an action result that is a success with a list of papers if the request succeeded, or a failure message
     * if the request failed
     */
    @Action(value = "getReferences", argsType = RelatedArgs.class)
    public ActionResult getReferences(RelatedArgs args) {
        return pojo().getReferences(args.id(), args.limit());
    }
}
