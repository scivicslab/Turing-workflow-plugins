package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Creates a sorted list of samples by parsing the header comment lines of Lisp files. It extracts the purpose, the
 * sequence of block relations, and the count of source references from lines starting with {@code ;;; use:}, {@code
 * ;;; shape:}, {@code ;;; source:}, and {@code ;;; also-seen:}.
 *
 * <ul>
 * <li>{@code entries(Map<String, String> samples)} returns a list of {@link Entry} objects, one for each map entry,
 * ordered by the sample name.</li>
 * <li>{@code shapeOf(String line)} returns a list of strings representing the blocks in a shape line, split by the
 * {@code →} character.</li>
 * <li>{@code nearest(List<Entry> entries, List<String> shape, int k)} returns at most {@code k} entries whose shape is
 * closest to the given {@code shape}, ordered by proximity.</li>
 * <li>{@code withAlsoSeen(String sample, String seen)} returns the sample text with a new {@code ;;; also-seen:} line
 * inserted immediately after the existing header lines.</li>
 * </ul>
 *
 * <p>The catalog is built from scratch each time it is requested; it holds no state between calls.</p>
 */
public final class SampleCatalog {

    private SampleCatalog() {}

    /**
     * Represents a single sample extracted from the head lines of a sample file.
     *
     * @param name the sample's name, its file name without {@code .lisp}
     * @param use what the structure does for its reader
     * @param shape each block's relations, in order
     * @param sources the number of sub-sections found with this structure, including the source and every also-seen
     * line
     */
    public record Entry(String name, String use, List<String> shape, int sources) {}

    /**
     * Returns a list of samples parsed from their header lines, ordered by name.
     *
     * @param samples a map where each key is a sample name and each value is the text of the sample's file, starting
     * with header lines beginning with {@code ;;; }
     *
     * @return a list of {@link Entry} objects, one for each sample in the map, sorted by name
     */
    public static List<Entry> entries(Map<String, String> samples) {
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> e : samples.entrySet()) {
            String use = "";
            List<String> shape = List.of();
            int sources = 0;
            for (String line : e.getValue().split("\\R")) {
                if (!line.startsWith(";;; ")) break;
                if (line.startsWith(";;; use:")) use = line.substring(8).strip();
                else if (line.startsWith(";;; shape:")) shape = shapeOf(line.substring(10));
                else if (line.startsWith(";;; source:") || line.startsWith(";;; also-seen:")) sources++;
            }
            out.add(new Entry(e.getKey(), use, shape, sources));
        }
        out.sort(Comparator.comparing(Entry::name));
        return List.copyOf(out);
    }

    /**
     * Splits a shape line into its constituent blocks.
     *
     * @param line the line containing blocks separated by the {@code →} character
     * @return a list of the blocks found in the line
     */
    public static List<String> shapeOf(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split("→")) {
            String p = part.strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * Returns at most {@code k} entries from {@code entries} whose shape is most similar to {@code shape}, ordered
     * from most to least similar. Similarity is measured by the minimum number of insertions, removals, or changes
     * required to transform one shape into the other.
     *
     * @param entries the list of samples to search through
     * @param shape the target sequence of block relations to match
     * @param k the maximum number of results to return
     * @return a list of up to {@code k} entries, ordered by proximity to {@code shape} and then by name
     */
    public static List<Entry> nearest(List<Entry> entries, List<String> shape, int k) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt((Entry e) -> distance(e.shape(), shape)).thenComparing(Entry::name));
        return List.copyOf(sorted.subList(0, Math.min(k, sorted.size())));
    }

    /**
     * Returns a string that is the text of the sample with a new {@code ;;; also-seen:} line inserted immediately
     * after the existing head lines.
     *
     * @param sample the text of the sample, including its head lines starting with {@code ;;; }
     * @param seen the text to be inserted after the {@code ;;; also-seen: } prefix
     * @return the modified sample text
     */
    public static String withAlsoSeen(String sample, String seen) {
        String[] lines = sample.split("\n", -1);
        int at = 0;
        while (at < lines.length && lines[at].startsWith(";;; ")) at++;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == at) sb.append(";;; also-seen: ").append(seen).append('\n');
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private static int distance(List<String> a, List<String> b) {
        int[] prev = new int[b.size() + 1];
        int[] cur = new int[b.size() + 1];
        for (int j = 0; j <= b.size(); j++) prev[j] = j;
        for (int i = 1; i <= a.size(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.size(); j++) {
                int cost = a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.size()];
    }
}
