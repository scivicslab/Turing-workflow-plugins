package com.scivicslab.turingworkflow.plugins.web;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * Fetches the content of web pages from a provided URL and returns it as an action result.
 *
 * <ul><li>{@code fetch} takes {@code UrlArgs} containing a URL and returns an action result containing the page
 * content as a success message, or an error message if the fetch fails.</li></ul>
 *
 * <p>This actor holds a {@code PageFetcher} instance. If the underlying {@code PageFetcher} is null, {@code isAlive()}
 * returns false and all actions fail.</p>
 *
 * <p>One instance may serve several workflows at once.</p>
 */
public class FetchActor extends IIActorRef<PageFetcher> {

    /**
     * Registers a new actor named {@code name} in the {@code system} that retrieves web page content using a {@link
     * PageFetcher}.
     *
     * @param name the name to register this actor under in the system
     * @param system the actor system this actor belongs to
     */
    public FetchActor(String name, IIActorSystem system) {
        super(name, new PageFetcher(), system);
    }

    private PageFetcher pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * Holds the address of a web page to be retrieved.
     *
     * @param url the page's address
     */
    public record UrlArgs(@NotNull String url) {}

    @Action(value = "fetch", argsType = UrlArgs.class)
    public ActionResult fetch(UrlArgs args) {
        return pojo().fetch(args.url());
    }

}