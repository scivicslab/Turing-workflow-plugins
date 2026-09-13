package com.scivicslab.turingworkflow.plugins.arxiv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the forms a person writes an arXiv id in.
 *
 * <p>Exercises the load-bearing path: whatever was pasted — a bare number, a version, the
 * {@code arXiv:} prefix, an abstract page or a PDF link — the client must ask arXiv about the same
 * paper. No network.</p>
 */
@DisplayName("ArxivClient — the id out of whatever was pasted")
class ArxivIdTest {

    @Test
    void everyFormOfOneId_narrowsToTheSameThing() {
        assertThat(ArxivClient.bareId("2203.02155")).isEqualTo("2203.02155");
        assertThat(ArxivClient.bareId("  2203.02155  ")).isEqualTo("2203.02155");
        assertThat(ArxivClient.bareId("arXiv:2203.02155")).isEqualTo("2203.02155");
        assertThat(ArxivClient.bareId("ARXIV:2203.02155")).isEqualTo("2203.02155");
        assertThat(ArxivClient.bareId("https://arxiv.org/abs/2203.02155")).isEqualTo("2203.02155");
        assertThat(ArxivClient.bareId("http://arxiv.org/abs/2203.02155v1")).isEqualTo("2203.02155v1");
        assertThat(ArxivClient.bareId("https://arxiv.org/pdf/2203.02155")).isEqualTo("2203.02155");
        assertThat(ArxivClient.bareId("https://arxiv.org/pdf/2203.02155v1.pdf")).isEqualTo("2203.02155v1");
    }

    @Test
    void anOldStyleId_keepsItsCategory() {
        assertThat(ArxivClient.bareId("cs/0701001")).isEqualTo("cs/0701001");
        assertThat(ArxivClient.bareId("https://arxiv.org/abs/cs/0701001")).isEqualTo("cs/0701001");
    }
}
