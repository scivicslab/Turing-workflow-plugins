package com.scivicslab.turingworkflow.plugins.kanakanji;

import java.util.Set;

/**
 * Validates whether Japanese text segments separated by {@code |} are correctly split into bunsetsu.
 *
 * <ul>
 * <li>{@code hasLoneParticle} returns true if any segment consists only of particles or punctuation, indicating the
 * line was split into words instead of bunsetsu.</li>
 * <li>{@code sameBoundaryCount} returns true if the {@code reading} and {@code kanji} strings contain the same number
 * of {@code |} characters.</li>
 * </ul>
 *
 * <p>One instance may serve several workflows or threads at once.</p>
 */
public final class BunsetsuCheck {

    private static final Set<String> PARTICLES = Set.of(
            "は", "が", "を", "に", "へ", "と", "で", "の", "も", "や", "か", "ね", "よ",
            "から", "まで", "より", "など", "だけ", "ばかり", "しか", "ので", "のに");

    private static final String PUNCTUATION = "、。，．,.!?！？";

    private BunsetsuCheck() {
    }

    /**
     * Returns true if any segment in the line consists only of particles or punctuation.
     *
     * @param line a string containing segments separated by {@code |}
     * @return true if a segment is empty or contains only a particle, false otherwise
     */
    public static boolean hasLoneParticle(String line) {
        for (String segment : line.split("\\|", -1)) {
            String bare = stripPunctuation(segment.strip());
            if (segment.isBlank()) {
                continue;
            }
            if (bare.isEmpty() || PARTICLES.contains(bare)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if both the reading and the kanji lines contain the same number of {@code |} separators.
     *
     * @param reading the line containing the reading, where segments are separated by {@code |}
     * @param kanji the line containing the kanji, where segments are separated by {@code |}
     * @return true if the number of {@code |} characters in reading equals the number of {@code |} characters in
     * kanji, false otherwise
     */
    public static boolean sameBoundaryCount(String reading, String kanji) {
        return count(reading) == count(kanji);
    }

    private static int count(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == '|') {
                n++;
            }
        }
        return n;
    }

    private static String stripPunctuation(String segment) {
        int end = segment.length();
        while (end > 0 && PUNCTUATION.indexOf(segment.charAt(end - 1)) >= 0) {
            end--;
        }
        return segment.substring(0, end);
    }
}
