package com.scivicslab.turingworkflow.plugins.semanticscholar;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for the forms a person writes a paper id in.
 *
 * <p>Exercises the load-bearing path: the Graph API takes an id with a prefix naming which
 * catalogue it belongs to, and what a person pastes is usually a bare DOI or arXiv number. No
 * network.</p>
 */
@DisplayName("SemanticScholarClient — the id the Graph API takes")
class SemanticScholarIdTest {

    @Test
    void aBareDoi_getsItsPrefix() {
        assertThat(SemanticScholarClient.resolveId("10.1038/s41586-021-03819-2"))
                .isEqualTo("DOI:10.1038/s41586-021-03819-2");
        assertThat(SemanticScholarClient.resolveId("  10.1038/s41586-021-03819-2  "))
                .isEqualTo("DOI:10.1038/s41586-021-03819-2");
    }

    @Test
    void aBareArxivNumber_getsItsPrefix() {
        assertThat(SemanticScholarClient.resolveId("2203.02155")).isEqualTo("arXiv:2203.02155");
        assertThat(SemanticScholarClient.resolveId("2203.02155v1")).isEqualTo("arXiv:2203.02155v1");
    }

    @Test
    void anIdThatAlreadySaysWhatItIs_isLeftAlone() {
        assertThat(SemanticScholarClient.resolveId("DOI:10.1038/nature14539"))
                .isEqualTo("DOI:10.1038/nature14539");
        assertThat(SemanticScholarClient.resolveId("arXiv:1706.03762")).isEqualTo("arXiv:1706.03762");
        assertThat(SemanticScholarClient.resolveId("PMID:12345678")).isEqualTo("PMID:12345678");
    }

    @Test
    void aSemanticScholarIdOfItsOwn_isLeftAlone() {
        String s2 = "649def34f8be52c8b66281af98ae884c09aef38b";
        assertThat(SemanticScholarClient.resolveId(s2)).isEqualTo(s2);
    }
}
