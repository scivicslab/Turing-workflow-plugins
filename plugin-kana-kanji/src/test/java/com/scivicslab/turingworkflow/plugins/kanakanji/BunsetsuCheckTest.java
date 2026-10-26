package com.scivicslab.turingworkflow.plugins.kanakanji;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BunsetsuCheckTest {

    @Test
    void bunsetsuLine_hasNoLoneParticle() {
        assertThat(BunsetsuCheck.hasLoneParticle("現代の|フォントでも、|一部の|フォントでは、")).isFalse();
    }

    @Test
    void wordLevelLine_hasLoneParticle() {
        assertThat(BunsetsuCheck.hasLoneParticle("巾級数|と|いうもの|は|この|種|の|一般的|な|方法")).isTrue();
    }

    @Test
    void particleWithTrailingPunctuation_isStillLone() {
        assertThat(BunsetsuCheck.hasLoneParticle("代表作に|『A』|など|。")).isTrue();
        assertThat(BunsetsuCheck.hasLoneParticle("代表作に|『A』|は、")).isTrue();
    }

    @Test
    void punctuationOnlySegment_isLone() {
        assertThat(BunsetsuCheck.hasLoneParticle("代表作に|『A』など|。")).isTrue();
    }

    @Test
    void contentWordStartingWithParticleLetter_isNotLone() {
        assertThat(BunsetsuCheck.hasLoneParticle("のどが|かわく|ひとが|いる")).isFalse();
    }

    @Test
    void lineWithoutSeparator_hasNoLoneParticle() {
        assertThat(BunsetsuCheck.hasLoneParticle("たとえば、")).isFalse();
    }

    @Test
    void sameBoundaryCount_comparesTheTwoSides() {
        assertThat(BunsetsuCheck.sameBoundaryCount("げんだいの|ふぉんとでも、", "現代の|フォントでも、")).isTrue();
        assertThat(BunsetsuCheck.sameBoundaryCount("うけ|せい|します。", "受精します。")).isFalse();
    }
}
