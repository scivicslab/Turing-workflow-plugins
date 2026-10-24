package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** Pure tests: the candidate lines, the question, and the outline numbered from the levels the model gives. */
class OutlineByModelTest {

    @Test
    void theModelsLevelsAreNumberedInOrderAndARepeatedTitleIsLeftOut() {
        var files = new TreeMap<String, String>(Map.of(
                "Chap01/p1.md", "## Chapter 1 Introduction\n\ntext\n\n## **Why Keycloak**\n\n```\n# not a heading\n```\n\n## TIP\n\n## Installing\n",
                "Chap01/p2.md", "## Why Keycloak\n\n## Running in a container\n"));
        List<OutlineByModel.Candidate> c = OutlineByModel.candidates(files);
        assertEquals(List.of("Chapter 1 Introduction", "Why Keycloak", "TIP", "Installing", "Why Keycloak", "Running in a container"),
                c.stream().map(OutlineByModel.Candidate::text).toList(), "a line of code is no candidate");
        String q = OutlineByModel.question("keycloak", c, 0, null);
        assertTrue(q.contains("[file Chap01/p1.md]\nL0 (after 0 lines of text): Chapter 1 Introduction\nL1 (after 1 lines of text): Why Keycloak\n"), q);
        var levels = OutlineByModel.read("**L0** | Chapter\nL1 | section\nL3 | section\nL4 | section\nL5 | subsection\nL2 | none\n");
        var o = OutlineByModel.outline(c, levels);
        assertEquals(List.of("1 chapter Chapter 1 Introduction", "1.1 section Why Keycloak", "1.2 section Installing", "1.2.1 subsection Running in a container"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList());
    }

    @Test
    void aBookWithFewHeadingsOffersItsShortPlainLinesToo() {
        var files = new TreeMap<String, String>(Map.of("p1.md", "第2章 開発プロセスの概要\n\nUMLはモデリング言語であり、モデリング方法論ではありません。\n\nプロセスの概観\n\n2.1 Inception\n"));
        assertEquals(List.of("第2章 開発プロセスの概要", "2.1 Inception"), OutlineByModel.candidates(files).stream().map(OutlineByModel.Candidate::text).toList());
    }

    @Test
    void aChaptersNameJoinsItsNumberAndTheBackOfTheBookComesLast() {
        var files = new TreeMap<String, String>(Map.of(
                "Assessments/p1.md", "## Chapter 1 answers\n",
                "Chap01/p1.md", "## CHAPTER ONE\n\n## Zeno, Eudoxus, Archimedes\n\n## 1. INTRODUCTION - - - - - - 3\n"));
        var ordered = OutlineByModel.inBookOrder(files);
        assertEquals(List.of("Chap01/p1.md", "Assessments/p1.md"), List.copyOf(ordered.keySet()));
        var c = OutlineByModel.candidates(ordered);
        assertEquals(List.of("CHAPTER ONE", "Zeno, Eudoxus, Archimedes", "Chapter 1 answers"), c.stream().map(OutlineByModel.Candidate::text).toList(),
                "a line of the contents with leaders is no candidate");
        var o = OutlineByModel.outline(c, OutlineByModel.read("L0 | chapter\nL1 | name\n"));
        assertEquals(List.of("1 chapter CHAPTER ONE Zeno, Eudoxus, Archimedes"), o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList());
    }

    @Test
    void chaptersReadOutOfOrderArePutInTheOrderOfTheirNumbers() {
        var c = List.of(new OutlineByModel.Candidate("a.md", "Authenticating Users", true), new OutlineByModel.Candidate("a.md", "Tokens", true),
                new OutlineByModel.Candidate("g.md", "Getting Started", true), new OutlineByModel.Candidate("g.md", "Installing", true));
        var o = OutlineByModel.outline(c, OutlineByModel.read("L0 | chapter 4\nL1 | section\nL2 | chapter 1\nL3 | section\n"));
        assertEquals(List.of("1 chapter Getting Started", "1.1 section Installing", "2 chapter Authenticating Users", "2.1 section Tokens"),
                o.nodes().stream().map(n -> n.number() + " " + n.level() + " " + n.title()).toList());
    }
}
