package com.scivicslab.turingworkflow.plugins.rstframe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of samples, read from the comment lines each sample begins with
 * ({@code FramesAndSamplesFromBooks_261021_oo01}): {@code ;;; use:}, {@code ;;; source:},
 * {@code ;;; shape:} and any number of {@code ;;; also-seen:}. Nothing else is kept about a sample,
 * so the catalogue is made afresh from the files each time it is needed.
 */
public final class SampleCatalog {

    private SampleCatalog() {}

    /**
     * One sample, as its head lines describe it.
     *
     * @param name    the sample's name, its file name without {@code .lisp}
     * @param use     what the structure does for its reader
     * @param shape   each block's relations, in order
     * @param sources how many sub-sections were found with this structure: the source and every also-seen
     */
    public record Entry(String name, String use, List<String> shape, int sources) {}

    /**
     * @param samples each sample's file text, by name
     * @return the samples, ordered by name
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

    /** @return a shape line's blocks */
    public static List<String> shapeOf(String line) {
        List<String> out = new ArrayList<>();
        for (String part : line.split("→")) {
            String p = part.strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * @return at most {@code k} samples whose shape is nearest to {@code shape}, nearest first: by
     *         how many blocks must be inserted, removed or changed to turn one into the other
     */
    public static List<Entry> nearest(List<Entry> entries, List<String> shape, int k) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt((Entry e) -> distance(e.shape(), shape)).thenComparing(Entry::name));
        return List.copyOf(sorted.subList(0, Math.min(k, sorted.size())));
    }

    /** @return the sample's text with one {@code ;;; also-seen:} line after its head lines */
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
