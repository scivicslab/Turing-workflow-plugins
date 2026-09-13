package com.scivicslab.turingworkflow.plugins.semanticscholar;

import com.scivicslab.pojoactor.action.ActionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Semantic Scholar の Graph API への問い合わせを保持する素のオブジェクト。アクターとして動かすのは
 * {@link SemanticScholarActor} の役目であり、「アクターであること」はこのクラスの性質ではない
 * （{@code ActorSuffixAndOwnedActorRef_260722_oo01}）。
 *
 * <p>What this reaches that OpenAlex and arXiv do not: the citation graph. {@code getCitations}
 * answers who cited a paper and {@code getReferences} what it cited, both as one call rather than
 * as a search to be assembled.</p>
 *
 * <p>A key is optional. Without one the API is shared and answers HTTP 429 when the pool is busy,
 * so this paces its requests and asks a refused one again, three times, before giving up. With one,
 * set it through {@code setApiKey}; it is sent as {@code x-api-key}.</p>
 *
 * <p>Base URL: {@code https://api.semanticscholar.org/graph/v1}</p>
 */
public class SemanticScholarClient {

    private static final Logger logger = Logger.getLogger(SemanticScholarClient.class.getName());

    private static final String BASE_URL = "https://api.semanticscholar.org/graph/v1";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_LIMIT = 10;

    /** Polite pace for the shared pool: the unauthenticated limit is counted per five minutes. */
    private static final long MIN_INTERVAL_MILLIS = 3000;

    /** How long to wait after the API says the pace was too fast, before asking once more. */
    private static final long RETRY_AFTER_MILLIS = 5000;

    /** How many times a refused request is asked again before the workflow is told. */
    private static final int RETRIES = 3;

    /** What a paper record carries in a list of results. */
    private static final String LIST_FIELDS =
            "title,year,authors,venue,citationCount,influentialCitationCount,externalIds,openAccessPdf";

    /** The same, with the abstract, for one paper on its own. */
    private static final String ONE_FIELDS = LIST_FIELDS + ",abstract,publicationTypes,tldr";

    /** How long an abstract is allowed to be in a list of results. */
    private static final int ABSTRACT_SNIPPET = 400;

    /**
     * When the last request went out, for the whole JVM rather than for one instance: the limit
     * belongs to the endpoint, not to a client object.
     */
    private static final Object PACE = new Object();
    private static long lastRequestAt = 0;

    private String apiKey = "";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * Sets the API key sent as {@code x-api-key}. Without one the shared pool is used.
     *
     * @param key the key Semantic Scholar issued
     */
    public ActionResult setApiKey(String key) {
        if (key == null || key.isBlank()) return new ActionResult(false, "API key is required");
        this.apiKey = key.trim();
        return new ActionResult(true, "API key set (" + this.apiKey.length() + " chars)");
    }

    /**
     * Searches for papers, ten of them, as the API ranks them.
     *
     * @param query what to look for
     */
    public ActionResult searchPapers(String query) {
        return searchPapersTopK(query, DEFAULT_LIMIT, null, null);
    }

    /**
     * Searches for papers with an explicit count and, optionally, a year range and field of study.
     *
     * @param query  what to look for
     * @param limit  how many to return; ten when absent
     * @param year   a year or a range such as {@code 2020-2024}; every year when absent
     * @param fields a field of study such as {@code Computer Science}; every field when absent
     */
    public ActionResult searchPapersTopK(String query, Integer limit, String year, String fields) {
        if (query == null || query.isBlank()) return new ActionResult(false, "Query is required");
        int count = limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/paper/search?query=").append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8))
                .append("&limit=").append(count)
                .append("&fields=").append(LIST_FIELDS);
        if (year != null && !year.isBlank()) {
            url.append("&year=").append(URLEncoder.encode(year.strip(), StandardCharsets.UTF_8));
        }
        if (fields != null && !fields.isBlank()) {
            url.append("&fieldsOfStudy=").append(URLEncoder.encode(fields.strip(), StandardCharsets.UTF_8));
        }
        try {
            JSONObject root = new JSONObject(get(url.toString()));
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.isEmpty()) return new ActionResult(true, "No papers found for: " + query);
            int total = root.optInt("total", data.length());
            StringBuilder sb = new StringBuilder();
            sb.append("Semantic Scholar papers for \"").append(query).append("\" (")
              .append(total).append(" total, showing ").append(data.length()).append("):\n\n");
            for (int i = 0; i < data.length(); i++) {
                sb.append(i + 1).append(". ").append(formatPaper(data.getJSONObject(i), false)).append("\n\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar search failed for: " + query, e);
            return new ActionResult(false, "Search failed: " + e.getMessage());
        }
    }

    /**
     * Reads one paper, with its abstract.
     *
     * @param id a Semantic Scholar id, or one of the forms the API takes as a prefix:
     *           {@code DOI:10.1038/…}, {@code arXiv:2203.02155}, {@code CorpusId:…},
     *           {@code PMID:…}. A bare DOI or arXiv id is prefixed here
     */
    public ActionResult getPaper(String id) {
        if (id == null || id.isBlank()) return new ActionResult(false, "Paper id is required");
        String url = BASE_URL + "/paper/" + URLEncoder.encode(resolveId(id), StandardCharsets.UTF_8)
                .replace("%3A", ":").replace("%2F", "/")
                + "?fields=" + ONE_FIELDS;
        try {
            return new ActionResult(true, formatPaper(new JSONObject(get(url)), true));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar getPaper failed for: " + id, e);
            return new ActionResult(false, "Failed to get paper: " + e.getMessage());
        }
    }

    /**
     * The open-access PDF of one paper, when Semantic Scholar knows of one.
     *
     * @param id the paper, in the same forms {@link #getPaper} takes
     */
    public ActionResult getPdfUrl(String id) {
        if (id == null || id.isBlank()) return new ActionResult(false, "Paper id is required");
        String url = BASE_URL + "/paper/" + URLEncoder.encode(resolveId(id), StandardCharsets.UTF_8)
                .replace("%3A", ":").replace("%2F", "/")
                + "?fields=title,openAccessPdf,externalIds";
        try {
            JSONObject paper = new JSONObject(get(url));
            JSONObject pdf = paper.optJSONObject("openAccessPdf");
            String link = pdf == null ? null : pdf.optString("url", null);
            if (link != null && !link.isBlank()) return new ActionResult(true, link);
            return new ActionResult(false, "No open-access PDF for: " + paper.optString("title", id));
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar getPdfUrl failed for: " + id, e);
            return new ActionResult(false, "Failed to get PDF URL: " + e.getMessage());
        }
    }

    /**
     * The papers that cite this one, newest first as the API returns them.
     *
     * @param id    the paper, in the same forms {@link #getPaper} takes
     * @param limit how many to return; ten when absent
     */
    public ActionResult getCitations(String id, Integer limit) {
        return related(id, limit, "citations", "citingPaper", "Papers citing");
    }

    /**
     * The papers this one cites.
     *
     * @param id    the paper, in the same forms {@link #getPaper} takes
     * @param limit how many to return; ten when absent
     */
    public ActionResult getReferences(String id, Integer limit) {
        return related(id, limit, "references", "citedPaper", "Papers cited by");
    }

    // ── internals ────────────────────────────────────────────────────────────

    private ActionResult related(String id, Integer limit, String path, String key, String heading) {
        if (id == null || id.isBlank()) return new ActionResult(false, "Paper id is required");
        int count = limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
        String url = BASE_URL + "/paper/" + URLEncoder.encode(resolveId(id), StandardCharsets.UTF_8)
                .replace("%3A", ":").replace("%2F", "/")
                + "/" + path + "?limit=" + count + "&fields=" + LIST_FIELDS;
        try {
            JSONObject root = new JSONObject(get(url));
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.isEmpty()) return new ActionResult(true, "None found for: " + id);
            StringBuilder sb = new StringBuilder(heading).append(" ").append(id)
                    .append(" (showing ").append(data.length()).append("):\n\n");
            for (int i = 0; i < data.length(); i++) {
                JSONObject paper = data.getJSONObject(i).optJSONObject(key);
                if (paper == null) continue;
                sb.append(i + 1).append(". ").append(formatPaper(paper, false)).append("\n\n");
            }
            return new ActionResult(true, sb.toString().stripTrailing());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Semantic Scholar " + path + " failed for: " + id, e);
            return new ActionResult(false, "Failed to get " + path + ": " + e.getMessage());
        }
    }

    /** @return what the API takes as a paper id: a bare DOI or arXiv id gets its prefix here */
    static String resolveId(String id) {
        String s = id.strip();
        if (s.contains(":")) return s;                      // already prefixed (DOI:, arXiv:, PMID:, …)
        if (s.startsWith("10.")) return "DOI:" + s;
        if (s.startsWith("https://doi.org/")) return "DOI:" + s.substring("https://doi.org/".length());
        if (s.matches("\\d{4}\\.\\d{4,5}(v\\d+)?")) return "arXiv:" + s;
        return s;                                            // a Semantic Scholar id
    }

    private static String formatPaper(JSONObject p, boolean verbose) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(p.optString("title", "(no title)")).append('"');
        int year = p.optInt("year", 0);
        if (year > 0) sb.append(" (").append(year).append(")");
        String venue = p.optString("venue", "");
        if (!venue.isBlank()) sb.append(", ").append(venue);
        sb.append(", cited ").append(p.optInt("citationCount", 0)).append(" times");
        int influential = p.optInt("influentialCitationCount", 0);
        if (influential > 0) sb.append(" (").append(influential).append(" influential)");
        sb.append('\n');

        JSONArray authors = p.optJSONArray("authors");
        if (authors != null && !authors.isEmpty()) {
            sb.append("Authors: ");
            int shown = verbose ? authors.length() : Math.min(5, authors.length());
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(", ");
                sb.append(authors.getJSONObject(i).optString("name", ""));
            }
            if (shown < authors.length()) sb.append(", … (").append(authors.length()).append(" total)");
            sb.append('\n');
        }

        JSONObject ids = p.optJSONObject("externalIds");
        if (ids != null) {
            String doi = ids.optString("DOI", null);
            if (doi != null && !doi.isBlank()) sb.append("DOI: https://doi.org/").append(doi).append('\n');
            String arxiv = ids.optString("ArXiv", null);
            if (arxiv != null && !arxiv.isBlank()) sb.append("arXiv: https://arxiv.org/abs/").append(arxiv).append('\n');
        }

        JSONObject tldr = p.optJSONObject("tldr");
        if (tldr != null) {
            String text = tldr.optString("text", "");
            if (!text.isBlank()) sb.append("TLDR: ").append(text).append('\n');
        }

        String summary = p.optString("abstract", "");
        if (!summary.isBlank()) {
            String text = summary.replaceAll("\\s+", " ").strip();
            sb.append("Abstract: ")
              .append(verbose || text.length() <= ABSTRACT_SNIPPET
                      ? text : text.substring(0, ABSTRACT_SNIPPET) + "…")
              .append('\n');
        }

        JSONObject pdf = p.optJSONObject("openAccessPdf");
        if (pdf != null) {
            String link = pdf.optString("url", "");
            if (!link.isBlank()) sb.append("PDF: ").append(link).append('\n');
        }

        String paperId = p.optString("paperId", "");
        if (!paperId.isBlank()) {
            sb.append("Semantic Scholar: https://www.semanticscholar.org/paper/").append(paperId);
        }
        return sb.toString().stripTrailing();
    }

    /**
     * One GET, waiting out a refusal rather than failing at the first one.
     *
     * <p>Semantic Scholar answers HTTP 429 when the pool it shares among callers without a key is busy. That
     * is a matter of when, not of what was asked, so this waits and asks again — three times, for
     * 5, 15 and 45 seconds, and only then reports the refusal to the workflow.</p>
     */
    private String get(String url) throws Exception {
        HttpResponse<String> resp = send(url);
        long wait = RETRY_AFTER_MILLIS;
        for (int attempt = 1; busy(resp.statusCode()) && attempt <= RETRIES; attempt++) {
            logger.warning("Semantic Scholar answered " + resp.statusCode() + "; waiting " + wait
                    + "ms and asking again (attempt " + attempt + " of " + RETRIES + ")");
            Thread.sleep(wait);
            wait *= 3;
            resp = send(url);
        }
        if (resp.statusCode() != 200) {
            throw new Exception("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /** @return whether the answer says "not now": too many requests, or the service is out */
    private static boolean busy(int status) {
        return status == 429 || status == 503;
    }

    private HttpResponse<String> send(String url) throws Exception {
        synchronized (PACE) {
            long since = System.currentTimeMillis() - lastRequestAt;
            if (lastRequestAt > 0 && since < MIN_INTERVAL_MILLIS) {
                Thread.sleep(MIN_INTERVAL_MILLIS - since);
            }
            lastRequestAt = System.currentTimeMillis();
        }
        logger.info("Semantic Scholar GET " + url);
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "TuringWorkflow/4.1.0")
                .header("Accept", "application/json")
                .GET();
        if (!apiKey.isBlank()) req.header("x-api-key", apiKey);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
