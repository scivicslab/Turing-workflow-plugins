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
 * Similar concepts ({@code FrameDataWorkflow_261024_oo01}, step 4): for the main concept of every smallest unit of
 * explanation, in every book of the database, the units elsewhere that explain a concept of the same kind, with the more
 * general concept the two share ("int" and "long": integer type; "for loop" and "while loop": loop). A writer who
 * explains a concept follows them to what was written about its kin; no generalized frame is built.
 *
 * <p>Each unit's main concept is embedded by its name alone: a longer text is dominated by the words a whole book shares
 * ("Java", "sequence of") and puts "integer types" nearer to anything in chapter 3 than to "floating-point types". The
 * units nearest to it from other sections are its candidates; each is shown to the model in one line of text (its main
 * concept, its axis, its members or parts, the main sentence of its first block); one question per unit
 * asks which candidates share a more general concept with it, and what that concept is. The answers are kept in a table
 * of their own, keyed by the criterion they were judged by, so that similarity judged another way can be put beside them
 * or in their place.
 */
public final class SimilarConcepts {

    /** The criterion of this class's judgements, kept with each of them. */
    public static final String CRITERION = "e5large-nearest8/common-general-concept";

    /** How many nearest units are a unit's candidates. */
    public static final int CANDIDATES = 8;

    /** One unit of a book, as this step reads it. */
    public record Unit(String book, String number, int ordinal, String concept, String text) {
        public String key() {
            return book + "|" + number + "|" + ordinal;
        }
    }

    /** One judgement: a unit, a similar unit, and the more general concept they share. */
    public record Similar(Unit unit, Unit other, String common) {}

    private SimilarConcepts() {
    }

    /** @return the units of every book, each with its line of text; a unit with no concept is left out */
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

    /** @return the embeddings of the texts, in order, from an OpenAI-compatible /v1/embeddings endpoint */
    public static List<float[]> embed(String url, String model, List<String> texts) throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
        JSONArray input = new JSONArray();
        // e5 compares two texts of the same kind with the query prefix on both
        for (String t : texts) input.put("query: " + t);
        String body = new JSONObject().put("model", model).put("input", input).toString();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
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

    /** @return the units nearest to {@code u}, at most {@code k}, leaving out the units of its own section */
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

    /** @return the question for one unit and its candidates */
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

    /** @return the similar units the reply names, with what they share; a candidate it names twice counts once */
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
     * Embeds the units whose text has no embedding yet, then asks, for every unit not judged yet under {@link #CRITERION}
     * ({@code only} empty: every book; else the sections named), which of its nearest units are of the same kind.
     *
     * @param embedUrl   the /v1/embeddings endpoint
     * @param embedModel the embedding model
     * @return how many units were judged and how many similar pairs they gave, in one line
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
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        return done.get() + " unit(s) judged under " + CRITERION + ": " + pairs.get() + " similar pair(s), " + failed.get() + " failed";
    }
}
