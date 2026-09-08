package com.scivicslab.turingworkflow.plugins.ocr;

import com.scivicslab.pojoactor.action.Action;
import com.scivicslab.pojoactor.action.ActionResult;

import jakarta.validation.constraints.NotNull;
import com.scivicslab.turingworkflow.workflow.IIActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

/**
 * {@link OcrClient} をアクターとして動かし、その操作をワークフローYAMLへ公開する。
 *
 * <p>状態は持たない。持つのは包んだ {@code OcrClient} のほうであり、これが {@code ActorRef} の
 * 前提である——アクターとは、素のオブジェクトと、それを動かす参照の対である。状態をこちら側に置き
 * {@code null} を包むと、{@code isAlive()} が偽を返し、{@code tell}/{@code ask} が黙って失敗する
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01} 「実例（2026-08-30）」）。</p>
 */
public class OcrActor extends IIActorRef<OcrClient> {

    /**
     * @param name   このアクターの登録名
     * @param system 所属するアクターシステム
     */
    public OcrActor(String name, IIActorSystem system) {
        super(name, new OcrClient(), system);
    }

    private OcrClient pojo() {
        return object;
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    /**
     * An address: either an OCR server's, or a PDF's.
     *
     * @param url the address
     */
    public record UrlArgs(@NotNull String url) {}

    /**
     * Which PDF to read, and which OCR server reads it.
     *
     * @param url     the PDF's address
     * @param backend {@code marker} or {@code yomitoku}; Marker when absent
     */
    public record OcrArgs(@NotNull String url, String backend) {}

    /**
     * Where to write text, and what text to write.
     *
     * @param path    the file to write; nothing is written when absent
     * @param content the text written there
     */
    public record WriteArgs(String path, @NotNull String content) {}

    /**
     * Which page of the downloaded PDF to read.
     *
     * @param page the zero-based page number
     */
    public record PageArgs(@NotNull Integer page) {}

    @Action(value = "setMarkerUrl", argsType = UrlArgs.class)
    public ActionResult setMarkerUrl(UrlArgs args) {
        return pojo().setMarkerUrl(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "setYomitokuUrl", argsType = UrlArgs.class)
    public ActionResult setYomitokuUrl(UrlArgs args) {
        return pojo().setYomitokuUrl(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "markerOcr", argsType = UrlArgs.class)
    public ActionResult markerOcr(UrlArgs args) {
        return pojo().markerOcr(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "yomitokuOcr", argsType = UrlArgs.class)
    public ActionResult yomitokuOcr(UrlArgs args) {
        return pojo().yomitokuOcr(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "ocr", argsType = OcrArgs.class)
    public ActionResult ocr(OcrArgs args) {
        return pojo().ocr(args.url(), args.backend());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "writeFile", argsType = WriteArgs.class)
    public ActionResult writeFile(WriteArgs args) {
        return pojo().writeFile(args.path(), args.content());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "downloadPdf", argsType = UrlArgs.class)
    public ActionResult downloadPdf(UrlArgs args) {
        return pojo().downloadPdf(args.url());
    }

    /**
     * @param args ワークフローからの引数
     * @return 包んだオブジェクトが返した結果
     */
    @Action(value = "markerOcrPage", argsType = PageArgs.class)
    public ActionResult markerOcrPage(PageArgs args) {
        return pojo().markerOcrPage(args.page());
    }

}