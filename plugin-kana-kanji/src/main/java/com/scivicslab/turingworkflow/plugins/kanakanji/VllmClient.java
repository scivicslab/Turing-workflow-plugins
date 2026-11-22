package com.scivicslab.turingworkflow.plugins.kanakanji;

import com.scivicslab.pojoactor.action.ActionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Calls a vLLM server via an OpenAI-compatible chat completions API to perform Japanese text segmentation and reading
 * generation.
 *
 * <ul>
 * <li>{@code setUrl} sets the vLLM endpoint URL.</li>
 * <li>{@code setModel} sets the model name.</li>
 * <li>{@code segment} takes OCR text and returns the text segmented into bunsetsu (one sentence per line, bunsetsu
 * separated by |).</li>
 * <li>{@code toHiragana} takes bunsetsu-segmented text and returns tab-separated hiragana-kanji pairs, one per
 * line.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 *
 * <p>An action fails if the input text is blank or if the vLLM server returns an error, in which case the result
 * message holds the error details.</p>
 */
public class VllmClient {

    private static final Logger logger = Logger.getLogger(VllmClient.class.getName());

    /** Default total limit of one request, in seconds. */
    public static final int DEFAULT_TOTAL_SECONDS = 600;

    /** Default idle limit of one response once its body has begun, in seconds. */
    public static final int DEFAULT_IDLE_SECONDS = 60;

    /** Seconds one request may take in all, queue wait and reading the body included. */
    private volatile int totalSeconds = DEFAULT_TOTAL_SECONDS;

    /** Seconds the response body may stall once its first byte has arrived. */
    private volatile int idleSeconds = DEFAULT_IDLE_SECONDS;

    private static final String SEGMENT_PROMPT = """
            /no_think
            以下は専門書の1ページ分のOCRテキストです。視覚的な改行で断片化されています。

            手順：
            1. 断片を結合して意味のある文・句にまとめる
            2. 各文・句を文節単位で | で区切る
            3. ページ番号のみの行は除外する

            文節とは、自立語（名詞・動詞・形容詞など）1つと、それに続く付属語（助詞・助動詞）をまとめた単位です。
            「ね」を入れて切れるところが文節の切れ目です。単語ごとに切ってはいけません。

            出力形式（1文・句につき1行）：
            漢字文（文節を|で区切る）

            例：
            巾級数と|いうものは|この|種の|一般的な|方法の|一つで

            ルール：
            - 助詞・助動詞・句読点だけの区切りを作らない。前の文節に付ける（「現代の」「フォントでも、」）
            - 複合語は分けない（「変数宣言記号」は1つ）
            - 数式・アルファベット・数字・英語はそのまま残す
            - 説明・コメント不要。出力行のみ

            OCRテキスト:
            %s""";

    private static final String HIRAGANA_PROMPT = """
            /no_think
            以下は文節で区切られた日本語テキストです（文節の区切りは|）。
            各行について、漢字をひらがなに変換した読みを付けて出力してください。

            出力形式（1行につき1行）：
            ひらがな読み（文節を|で区切る）\t漢字文（文節を|で区切る）

            例（入力）：
            巾級数と|いうものは|この|種の|一般的な|方法の|一つで

            例（出力）：
            べききゅうすうと|いうものは|この|しゅの|いっぱんてきな|ほうほうの|ひとつで\t巾級数と|いうものは|この|種の|一般的な|方法の|一つで

            ルール：
            - 区切りの | は、入力と同じ位置に、同じ数だけ置く。増やしたり減らしたりしない
            - 左（ひらがな読み）と右（漢字文）の | の数は同じにする
            - 数式・アルファベット・数字・英語はひらがな読みなしでそのまま右側に残す
            - ひらがな・カタカナはそのままひらがなに変換する
            - 助詞の「は」「へ」「を」は発音（わ／え／お）ではなく表記どおり「は」「へ」「を」と書く
            - 説明・コメント不要。出力行のみ

            入力テキスト:
            %s""";

    private String vllmUrl = "http://192.168.5.16:8000/v1/chat/completions";
    private String model = "Qwen3.5-35B-A3B";
    private String lastResponse = "";

    public ActionResult setUrl(String url) {
        if (url == null || url.isBlank()) {
            return new ActionResult(false, "URL is required");
        }
        this.vllmUrl = url;
        return new ActionResult(true, "URL set to " + this.vllmUrl);
    }

    /**
     * Sets the time limits of each request to the vLLM server; a {@code null} keeps the current value.
     *
     * <p>{@code totalSeconds} (default {@value #DEFAULT_TOTAL_SECONDS}) bounds a whole request, from sending it to
     * the end of the response body, so it also covers the time the request waits in the GPU broker's background queue
     * and the generation, during which the server sends nothing. {@code idleSeconds} (default {@value
     * #DEFAULT_IDLE_SECONDS}) applies only after the first byte of the response body has arrived.</p>
     *
     * @param totalSeconds the total limit in seconds, or {@code null}
     * @param idleSeconds the idle limit in seconds, or {@code null}
     * @return a success naming the limits now in force, or a failure if a value is not positive
     */
    public ActionResult setTimeLimits(Integer totalSeconds, Integer idleSeconds) {
        if ((totalSeconds != null && totalSeconds <= 0) || (idleSeconds != null && idleSeconds <= 0)) {
            return new ActionResult(false, "setTimeLimits: limits must be positive seconds");
        }
        if (totalSeconds != null) this.totalSeconds = totalSeconds;
        if (idleSeconds != null) this.idleSeconds = idleSeconds;
        return new ActionResult(true, "vLLM limits: total " + this.totalSeconds + " s, idle " + this.idleSeconds + " s");
    }

    public ActionResult setModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return new ActionResult(false, "Model name is required");
        }
        this.model = modelName;
        return new ActionResult(true, "Model set to " + this.model);
    }

    /**
     * Segments OCR text into bunsetsu-delimited sentences by calling a vLLM server. Returns an action result
     * containing the segmented kanji text (one sentence per line, bunsetsu separated by |) on success, or an error
     * message if the text is blank or the server call fails.
     *
     * @param ocrText the raw text from OCR, which may contain visual line breaks from the document layout
     */
    public ActionResult segment(String ocrText) {
        String text = ocrText == null ? "" : ocrText;
        if (text.isBlank()) {
            return new ActionResult(false, "OCR text is required");
        }
        String prompt = SEGMENT_PROMPT.formatted(text);
        try {
            String response = fixLoneParticles(callVllm(prompt));
            this.lastResponse = response;
            logger.info("Step1 segmentation: " + response.lines().count() + " lines");
            return new ActionResult(true, response);
        } catch (Exception e) {
            logger.log(Level.WARNING, "vLLM call failed (segment)", e);
            return new ActionResult(false, "vLLM error: " + e.getMessage());
        }
    }

    /**
     * Converts bunsetsu-segmented kanji text into tab-separated hiragana and kanji pairs.
     *
     * Returns an action result containing a string where each line consists of a hiragana reading and the original
     * kanji text, separated by a tab. If the input is blank, the result is a failure.
     *
     * If the vLLM server is unreachable or returns an error, the result is a failure with an error message.
     *
     * @param segmentedText the text to convert, where each line contains a single bunsetsu-segmented sentence.
     * @return an action result that is a success with the tab-separated pairs if the conversion succeeds, or a failure
     * with an error message if the service fails.
     */
    public ActionResult toHiragana(String segmentedText) {
        String text = segmentedText == null ? "" : segmentedText;
        if (text.isBlank()) {
            return new ActionResult(false, "Segmented text is required");
        }
        List<String> inputLines = text.lines()
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toList();

        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < inputLines.size(); i += BATCH_SIZE) {
                List<String> batch = inputLines.subList(i, Math.min(i + BATCH_SIZE, inputLines.size()));
                List<String> batchResults = processBatch(batch);
                results.addAll(batchResults);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "vLLM call failed (toHiragana)", e);
            return new ActionResult(false, "vLLM error: " + e.getMessage());
        }

        String combined = String.join("\n", results);
        logger.info("Step2 hiragana: " + results.size() + " lines total");
        return new ActionResult(true, combined);
    }

    /**
     * Attempts to re-segment lines that consist only of particles or punctuation by calling the LLM again for each
     * such line individually.
     *
     * <p>If a line is found to contain only particles or punctuation, it is sent to the vLLM server as a new request.
     * If the new response provides a single line that successfully breaks the particles into a proper bunsetsu, that
     * new line is used; otherwise, the original line is kept to ensure no content is lost.</p>
     *
     * @param response the segmented kanji text containing potential lone-particle lines
     * @return the text with corrected segmentation for problematic lines
     */
    private String fixLoneParticles(String response) {
        List<String> fixed = new ArrayList<>();
        for (String line : response.lines().toList()) {
            if (!BunsetsuCheck.hasLoneParticle(line)) {
                fixed.add(line);
                continue;
            }
            String again = line;
            try {
                String reply = callVllm(SEGMENT_PROMPT.formatted(line.replace("|", "")));
                List<String> replyLines = reply.lines().map(String::strip).filter(l -> !l.isBlank()).toList();
                if (replyLines.size() == 1 && !BunsetsuCheck.hasLoneParticle(replyLines.get(0))) {
                    again = replyLines.get(0);
                } else {
                    logger.info("Kept a line split into words: " + line);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "vLLM call failed (re-segment), keeping the line", e);
            }
            fixed.add(again);
        }
        return String.join("\n", fixed);
    }

    private static final int BATCH_SIZE = 20;
    private static final int MAX_LINE_RETRIES = 2;

    /**
     * Returns a list of processed lines where Japanese text is paired with hiragana readings and non-Japanese text is
     * passed through.
     *
     * <p>The method identifies lines containing Japanese characters to send to the vLLM server in a single batch,
     * while lines containing only ASCII, numbers, or formulas are immediately formatted as a tab-separated pair with
     * an empty reading. For the Japanese lines, it validates that the LLM's response provides a valid hiragana reading
     * and maintains the original bunsetsu boundaries; if a response line is invalid, it retries that specific line
     * individually up to {@value #MAX_LINE_RETRIES} times. If all retries fail, the line is returned as a kanji-only
     * pair with an empty reading.</p>
     *
     * @param inputLines the list of segmented text lines to process
     * @return a list of strings, where each string is either a tab-separated hiragana-kanji pair or a pass-through
     * line formatted with a leading tab
     *
     * @throws Exception if the vLLM server returns an error or the HTTP request fails
     */
    private List<String> processBatch(List<String> inputLines) throws Exception {
        // Separate Japanese lines from pass-through lines
        List<Integer> japaneseIndices = new ArrayList<>();
        List<String> japaneseLines = new ArrayList<>();
        String[] results = new String[inputLines.size()];

        for (int i = 0; i < inputLines.size(); i++) {
            String line = inputLines.get(i);
            if (containsJapanese(line)) {
                japaneseIndices.add(i);
                japaneseLines.add(line);
            } else {
                // Non-Japanese: pass through as kanji-only pair
                results[i] = "\t" + line;
            }
        }

        if (!japaneseLines.isEmpty()) {
            String batchInput = String.join("\n", japaneseLines);
            String response = callVllm(HIRAGANA_PROMPT.formatted(batchInput));

            List<String> responseLines = response.lines()
                    .map(String::strip)
                    .filter(l -> !l.isBlank())
                    .toList();

            // Align response lines to Japanese input lines by index
            for (int j = 0; j < japaneseLines.size(); j++) {
                String inputLine = japaneseLines.get(j);
                String responseLine = j < responseLines.size() ? responseLines.get(j) : "";
                int origIndex = japaneseIndices.get(j);

                if (isAcceptable(inputLine, responseLine)) {
                    results[origIndex] = responseLine.isEmpty() ? "\t" + inputLine : responseLine;
                } else {
                    results[origIndex] = retryLine(inputLine);
                }
            }
        }

        List<String> out = new ArrayList<>(java.util.Arrays.asList(results));
        logger.info("Batch of " + inputLines.size() + " lines processed ("
                + japaneseLines.size() + " Japanese, "
                + (inputLines.size() - japaneseLines.size()) + " pass-through)");
        return out;
    }

    /**
     * Returns true if the response line contains a valid hiragana reading for the input line's kanji and maintains the
     * same number of bunsetsu boundaries.
     *
     * <p>A response line is acceptable if it contains Japanese characters and follows the tab-separated format where
     * the left column contains hiragana and the number of {@code |} separators matches the input line. Lines
     * containing no Japanese characters are always considered acceptable.</p>
     *
     * @param inputLine the original segmented text line to validate
     * @param responseLine the line returned by the LLM to be checked
     * @return true if the response line is a valid hiragana-kanji pair for the input, false otherwise
     */
    private boolean isAcceptable(String inputLine, String responseLine) {
        if (!containsJapanese(inputLine)) {
            return true; // No Japanese → no reading required
        }
        if (responseLine.isBlank() || !responseLine.contains("\t")) {
            return false;
        }
        String[] columns = responseLine.split("\t", 2);
        return containsHiragana(columns[0]) && BunsetsuCheck.sameBoundaryCount(columns[0], columns[1]);
    }

    /**
     * Attempts to convert a single line of text into a hiragana-kanji pair by calling the vLLM server.
     *
     * @param inputLine the segmented kanji text to be converted.
     * @return a tab-separated string containing the hiragana reading and the original kanji text, or a string
     * containing only a tab and the original input if all attempts fail.
     *
     * @throws Exception if the vLLM server call fails.
     */
    private String retryLine(String inputLine) throws Exception {
        for (int attempt = 1; attempt <= MAX_LINE_RETRIES; attempt++) {
            logger.info("Retrying line (attempt " + attempt + "): " + inputLine);
            String response = callVllm(HIRAGANA_PROMPT.formatted(inputLine));
            String line = response.lines()
                    .map(String::strip)
                    .filter(l -> !l.isBlank())
                    .findFirst()
                    .orElse("");
            if (isAcceptable(inputLine, line)) {
                return line;
            }
        }
        // Give up: return input as kanji-only pair (empty hiragana)
        logger.warning("Giving up on line after " + MAX_LINE_RETRIES + " retries: " + inputLine);
        return "\t" + inputLine;
    }

    private static boolean containsJapanese(String text) {
        for (char c : text.toCharArray()) {
            if ((c >= '\u3041' && c <= '\u3096') // hiragana
                    || (c >= '\u30A1' && c <= '\u30F6') // katakana
                    || (c >= '\u4E00' && c <= '\u9FFF') // CJK unified ideographs
                    || (c >= '\u3400' && c <= '\u4DBF')) { // CJK extension A
                return true;
            }
        }
        return false;
    }

    private static boolean containsHiragana(String text) {
        for (char c : text.toCharArray()) {
            if (c >= '\u3041' && c <= '\u3096') return true;
        }
        return false;
    }

    /** The most tokens one answer may have; a reply that reaches it is cut off and fails. */
    private static final int MAX_TOKENS = 8192;

    private String callVllm(String prompt) throws Exception {
        String body = """
                {
                    "model": "%s",
                    "messages": [{"role": "user", "content": %s}],
                    "max_tokens": %d,
                    "temperature": 0.0,
                    "chat_template_kwargs": {"enable_thinking": false}
                }
                """.formatted(model, jsonEscape(prompt), MAX_TOKENS);

        logger.info("vLLM request URL: " + vllmUrl);
        logger.info("vLLM request body (first 500 chars): " + body.substring(0, Math.min(500, body.length())));

        HttpClient client = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(vllmUrl))
                .header("Content-Type", "application/json; charset=utf-8")
                // Bulk generation: tell quarkus-gpu-broker to queue this behind interactive requests
                // (the chat path defaults to foreground), and who is asking.
                .header("X-Job-Priority", "background")
                .header("X-Submitter-Id", "kana-kanji-pairs")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        // the total limit covers the broker queue and the generation, which send nothing, and the body as well
        LimitedExchange.Reply response = LimitedExchange.send(client, request, totalSeconds, idleSeconds,
                "POST " + vllmUrl + " (model " + model + ")");

        if (response.status() != 200) {
            throw new RuntimeException("HTTP " + response.status() + ": " + response.body());
        }

        return extractContent(response.body());
    }

    /**
     * Returns {@code choices[0].message.content} of an OpenAI-compatible response, stripped.
     *
     * @throws RuntimeException if the body has no answer, if the model stopped at the token limit ({@code
     * finish_reason} {@code length}: the reply is cut off), or if the answer's text is empty
     */
    private String extractContent(String jsonBody) {
        org.json.JSONObject choice;
        try {
            choice = new org.json.JSONObject(jsonBody).getJSONArray("choices").getJSONObject(0);
        } catch (org.json.JSONException e) {
            throw new RuntimeException("No answer in response: " + jsonBody.substring(0, Math.min(200, jsonBody.length())), e);
        }
        if ("length".equals(choice.optString("finish_reason", null))) {
            throw new RuntimeException("reply cut off at max_tokens=" + MAX_TOKENS);
        }
        org.json.JSONObject message = choice.optJSONObject("message");
        String content = message == null || message.isNull("content") ? null : message.optString("content", null);
        if (content == null || content.isBlank()) throw new RuntimeException("empty reply");
        return content.strip();
    }

    private static String jsonEscape(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

}
