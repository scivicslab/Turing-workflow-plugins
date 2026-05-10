package com.scivicslab.turingworkflow.plugins.report;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportBuilderTest {

    private ReportBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ReportBuilder(); // no actor system — tests legacy section path
    }

    private static ReportSection section(String title, String content) {
        return new ReportSection() {
            @Override public String getTitle() { return title; }
            @Override public String getContent() { return content; }
        };
    }

    @Test
    void build_emptyBuilder_returnsHeader() {
        String report = builder.build();
        assertThat(report).contains("=== Workflow Execution Report ===");
    }

    @Test
    void addSection_andBuild_includesContent() {
        builder.addSection(section("My Section", "some content here"));
        String report = builder.build();
        assertThat(report).contains("My Section");
        assertThat(report).contains("some content here");
    }

    @Test
    void addSection_multipleInOrder_outputInOrder() {
        builder.addSection(section("First", "content-1"));
        builder.addSection(section("Second", "content-2"));
        String report = builder.build();
        int pos1 = report.indexOf("content-1");
        int pos2 = report.indexOf("content-2");
        assertThat(pos1).isLessThan(pos2);
    }

    @Test
    void addSection_nullContent_sectionSkipped() {
        builder.addSection(section("Empty", null));
        String report = builder.build();
        assertThat(report).doesNotContain("Empty");
    }

    @Test
    void addSection_emptyContent_sectionSkipped() {
        builder.addSection(section("Empty", ""));
        String report = builder.build();
        assertThat(report).doesNotContain("Empty");
    }

    @Test
    void addSection_nullTitle_contentStillIncluded() {
        builder.addSection(section(null, "untitled content"));
        String report = builder.build();
        assertThat(report).contains("untitled content");
    }
}
