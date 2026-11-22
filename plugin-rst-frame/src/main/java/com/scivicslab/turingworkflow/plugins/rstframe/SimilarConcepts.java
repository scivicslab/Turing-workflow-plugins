package com.scivicslab.turingworkflow.plugins.rstframe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Identifies pairs of similar concepts across books and the more general concept they share, such as "int" and "long"
 * both being "integer types".
 *
 * <p>The class offers the following operations:
 * <ul>
 * <li>{@code units}: returns a list of all units in the database, each containing its book, section, ordinal, main
 * concept, and a text representation.</li>
 * <li>{@code embed}: returns a list of vectors for the provided texts by calling an OpenAI-compatible embedding
 * service.</li>
 * <li>{@code nearest}: returns up to {@code k} units from the provided list that are closest to the given unit {@code
 * u} based on their vectors, excluding units from the same section.</li>
 * <li>{@code question}: returns a formatted string containing a prompt for a model to judge the similarity of a unit
 * against a list of candidates.</li>
 * <li>{@code read}: returns a list of {@link Similar} records parsed from a model's text reply.</li>
 * <li>{@code askAll}: embeds missing units and performs the similarity judgment for all unjudged units (or a specific
 * subset) using a local LLM.</li>
 * </ul>
 *
 * <p>One instance of this class is not used; all operations are static.
 *
 * <p>A typical workflow step uses:
 * <pre>{@code
 * askAll(store, "http://localhost:8080/v1/embeddings", "e5-large", List.of(), writer, 4, log);
 * }</pre>
 */
public final class SimilarConcepts {

    /** The criterion used to label each similarity judgement in the database. */
    public static final String CRITERION = "e5large-nearest8/common-general-concept";

    /** The number of nearest units used as candidates for similarity comparison. */
    public static final int CANDIDATES = 8;

    /**
     * Represents a single smallest unit of explanation within a book.
     *
     * @param book the name of the book
     * @param number the section number in the book
     * @param ordinal the sequence number of the unit within the section
     * @param concept the main concept of the unit
     * @param text a string containing the concept, the axis, the members or parts, and the first nucleus sentence
     */
    public record Unit(String book, String number, int ordinal, String concept, String text) {
        public String key() {
            return book + "|" + number + "|" + ordinal;
        }
    }

    /**
     * Represents a single judgement that a unit and another unit are of the same kind, along with the more general
     * concept they share.
     *
     * @param unit the first unit
     * @param other the similar unit
     * @param common the more general concept they share
     */
    public record Similar(Unit unit, Unit other, String common) {}

    private SimilarConcepts() {
    }

    /**
     * Returns a list of all units from every book in the store, where each unit contains its main concept and a text
     * string describing its axis, members or parts, and the first block's nucleus.
     *
     * <p>A unit with no concept is omitted from the list.
     *
     * @param store the source of books and section groups
     */
    public static List<Unit> units(BookStore store) throws Exception {
        List<Unit> out = new ArrayList<>();
        for (String book : store.books()) {
            for (Map.Entry<String, List<SectionGroups.Group>> e : store.groups(book).entrySet()) {
                BookStore.SectionRules r = store.sectionRules(book, e.getKey());
                Map<String, String> nucleus = new LinkedHashMap<>();
                if (r != null && r.rules() != null) {
                    for (SectionGroups.Block b : SectionGroups.blocks(r.rules(), r.code())) {
                        for (String line : b.text().split("\n")) {
                            if (line.startsWith("nucleus: ")) {
                                nucleus.put(b.name(), line.substring(9));
                                break;
                            }
                        }
                    }
                }
                for (SectionGroups.Group g : e.getValue()) {
                    if (g.concept() == null || g.concept().isBlank()) continue;
                    List<String> members = g.blocks().stream().map(SectionGroups.Member::name)
                            .filter(n -> n != null && !n.isBlank() && !n.startsWith("(")).distinct().toList();
                    String first = nucleus.get(g.first());
                    String text = g.concept() + ". " + g.type() + (members.isEmpty() ? "" : " of: " + String.join("; ", members)) + "."
                            + (first == null ? "" : " " + (first.length() > 300 ? first.substring(0, 300) : first));
                    out.add(new Unit(book, e.getKey(), g.ordinal(), g.concept(), text));
                }
            }
        }
        return out;
    }

    /**
     * Returns a list of normalized embedding vectors for the given texts by calling an OpenAI-compatible {@code
     * /v1/embeddings} endpoint.
     *
     * @param url the URI of the embedding endpoint
     * @param model the name of the model to use for embedding
     * @param texts the list of strings to be embedded
     * @return a list of float arrays, where each array is a normalized vector corresponding to the text at the same
     * index in {@code texts}
     *
     * @throws IllegalStateException if the endpoint returns a status code other than 200
     */
    public static List<float[]> embed(String url, String model, List<String> texts) throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
        JSONArray input = new JSONArray();
        // e5 compares two texts of the same kind with the query prefix on both
        for (String t : texts) input.put("query: " + t);
        String body = new JSONObject().put("model", model).put("input", input).toString();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json").header("X-Job-Priority", "background")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body());
        JSONArray data = new JSONObject(res.body()).getJSONArray("data");
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONArray v = data.getJSONObject(i).getJSONArray("embedding");
            float[] f = new float[v.length()];
            double norm = 0;
            for (int j = 0; j < f.length; j++) {
                f[j] = (float) v.getDouble(j);
                norm += f[j] * f[j];
            }
            float n = (float) Math.sqrt(norm);
            for (int j = 0; j < f.length; j++) f[j] /= n;
            out.add(f);
        }
        return out;
    }

    /**
     * Returns at most k units from the provided list that are most similar to {@code u} based on their embeddings,
     * excluding any units from the same section as {@code u}.
     *
     * @param u the unit to find matches for
     * @param units the list of all units to search through
     * @param vectors a map of unit keys to their embedding vectors
     * @param k the maximum number of similar units to return; a value of 0 or less returns an empty list
     * @return a list of up to k similar units, ordered by descending similarity score
     */
    public static List<Unit> nearest(Unit u, List<Unit> units, Map<String, float[]> vectors, int k) {
        float[] a = vectors.get(u.key());
        if (a == null) return List.of();
        record Scored(Unit unit, double score) {}
        List<Scored> s = new ArrayList<>();
        for (Unit o : units) {
            if (o.book().equals(u.book()) && o.number().equals(u.number())) continue;
            float[] b = vectors.get(o.key());
            if (b == null) continue;
            double d = 0;
            for (int i = 0; i < a.length; i++) d += a[i] * b[i];
            s.add(new Scored(o, d));
        }
        s.sort(Comparator.comparingDouble(Scored::score).reversed());
        return s.stream().limit(k).map(Scored::unit).toList();
    }

    /**
     * Returns a text prompt that asks a model to identify which of the provided candidates share a more general
     * concept with the main concept of {@code u}.
     *
     * <p>The prompt contains the text of {@code u}, followed by the text of each unit in {@code candidates} labeled as
     * c1, c2, etc. It instructs the model to identify candidates that are of the same kind by naming the candidate
     * index and the shared general concept (e.g., "c2 | integer type"), or to respond with "none" if no such
     * candidates exist.
     *
     * @param u the unit whose main concept is being described
     * @param candidates the list of nearby units to be presented as potential matches
     */
    public static String question(Unit u, List<Unit> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) sb.append("c").append(i + 1).append(": ").append(candidates.get(i).text()).append('\n');
        return "A passage of a textbook explains this concept:\n\n" + u.text() + "\n\n"
                + "Here are concepts other passages explain:\n\n" + sb + "\n"
                + "Which of them are of the same kind as the first concept: both are kinds or cases of one more general concept, as "
                + "\"int\" and \"long\" are both integer types, \"for loop\" and \"while loop\" are both loops, \"a comment\" and an "
                + "\"annotation\" are both notes in source code that the compiler does not run? A concept that is a part of the first, "
                + "an example of it, or only mentioned with it is not of the same kind. For each one of the same kind, write its "
                + "name, \" | \", and the more general concept the two share, in a few words. For example\n"
                + "c2 | integer type\nc5 | loop\n"
                + "Write \"none\" when none is of the same kind. Write nothing else.";
    }

    /**
     * Parses a text reply to extract the similar units and the more general concept they share.
     *
     * Returns a list of {@link Similar} objects representing the matches found in the reply, where each match links
     * the unit {@code u} to a candidate and the identified common concept.
     *
     * A candidate named multiple times in the reply results in a single entry in the list.
     *
     * @param reply the text containing the names of candidates and their shared concepts in the format "c[number] |
     * [concept]";
     *
     * @param u the unit being compared;
     * @param candidates the list of possible units to match against;
     * @return a list of similarities found in the reply, which is empty if no valid matches are found or if the reply
     * contains "none".
     */
    public static List<Similar> read(String reply, Unit u, List<Unit> candidates) {
        Map<Integer, Similar> by = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(?im)^\\W*c(\\d+)\\W*\\|\\s*(.+?)\\s*$").matcher(reply);
        while (m.find()) {
            int i = Integer.parseInt(m.group(1)) - 1;
            String common = m.group(2).replaceAll("^[\\s*_`\"]+|[\\s*_`\".]+$", "");
            if (i < 0 || i >= candidates.size() || by.containsKey(i) || common.isBlank() || common.equalsIgnoreCase("none")) continue;
            by.put(i, new Similar(u, candidates.get(i), common));
        }
        return new ArrayList<>(by.values());
    }

    /**
     * Performs a full pass of the similarity identification process by embedding missing unit concepts and asking a
     * model to identify similar concepts for all unjudged units.
     *
     * <p>If {@code only} is empty, it processes every unit in the {@code store} that has not yet been judged under
     * {@link #CRITERION}; otherwise, it only processes units belonging to the sections listed in {@code only}.
     *
     * <p>Returns a string summarizing the number of units judged, the number of similar pairs found, and the number of
     * units that failed.
     *
     * @param store the source of books and units
     * @param embedUrl the /v1/embeddings endpoint
     * @param embedModel the name of the embedding model
     * @param only a list of section numbers to restrict the work to, or an empty list to process all unjudged units
     * @param writer a function that takes the question text and returns the model's reply
     * @param parallel the number of units to process at a time
     * @param log a consumer for progress and error messages
     * @return a string in the form "X unit(s) judged under [criterion]: Y similar pair(s), Z failed"
     * @throws Exception if an error occurs during embedding or if the model's reply is invalid
     */
    public static String askAll(BookStore store, String embedUrl, String embedModel, List<String> only, Function<String, String> writer,
            int parallel, Consumer<String> log) throws Exception {
        List<Unit> units = units(store);
        Map<String, float[]> vectors = store.unitEmbeddings(embedModel);
        List<Unit> missing = units.stream().filter(u -> !vectors.containsKey(u.key() + "\t" + u.concept())).toList();
        log.accept("embedding " + missing.size() + " of " + units.size() + " unit(s)");
        for (int i = 0; i < missing.size(); i += 32) {
            List<Unit> batch = missing.subList(i, Math.min(missing.size(), i + 32));
            List<float[]> vs = embed(embedUrl, embedModel, batch.stream().map(Unit::concept).toList());
            for (int j = 0; j < batch.size(); j++) {
                store.saveUnitEmbedding(embedModel, batch.get(j), vs.get(j));
                vectors.put(batch.get(j).key() + "\t" + batch.get(j).concept(), vs.get(j));
            }
        }
        // the vectors of the units as they stand, by unit
        Map<String, float[]> current = new LinkedHashMap<>();
        for (Unit u : units) current.put(u.key(), vectors.get(u.key() + "\t" + u.concept()));
        Set<String> judged = store.similarJudged(CRITERION);
        List<Unit> todo = units.stream().filter(u -> only.isEmpty() ? !judged.contains(u.key() + "\t" + u.concept()) : only.contains(u.number())).toList();
        log.accept("start: " + todo.size() + " unit(s) to judge, " + parallel + " at a time");
        AtomicInteger done = new AtomicInteger(), failed = new AtomicInteger(), pairs = new AtomicInteger();
        BatchFailures failures = new BatchFailures();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Unit u : todo) {
                fs.add(pool.submit(() -> {
                    try {
                        List<Unit> cands = nearest(u, units, current, CANDIDATES);
                        String reply = cands.isEmpty() ? "none" : writer.apply(question(u, cands));
                        List<Similar> sim = read(reply, u, cands);
                        if (sim.isEmpty() && !reply.toLowerCase(java.util.Locale.ROOT).contains("none")) throw new IllegalStateException("the reply names no candidate and does not say none");
                        store.saveSimilar(CRITERION, u, sim);
                        pairs.addAndGet(sim.size());
                        int n = done.incrementAndGet();
                        if (n % 50 == 0 || n == todo.size()) log.accept("done " + n + "/" + todo.size() + ", " + pairs.get() + " similar pair(s) so far");
                    } catch (Exception ex) {
                        failed.incrementAndGet();
                        log.accept("FAILED " + u.key() + ": " + ex.getMessage());
                        failures.add(u.key(), ex);
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return failures.outcome(todo.size(), done.get() + " unit(s) judged under " + CRITERION + ": " + pairs.get() + " similar pair(s), " + failed.get() + " failed");
    }
}
