package com.scivicslab.turingworkflow.plugins.rstframe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.scivicslab.pojoactor.action.ActionResult;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;
import com.sun.net.httpserver.HttpServer;

/**
 * A batch whose items partly fail is a failure: the batch says how many of how many failed and, for each failed item,
 * its id and reason; the items that succeeded stay saved, so a rerun redoes only the failed ones.
 */
@DisplayName("rst-frame batches - one failed item makes the batch fail, naming the item and the reason")
class BatchPartialFailureTest {

    /** A book with sections 4.1 (a rule file without sentences, so it is not asked) and 4.1.1 (two sentences). */
    private static String bookWithTwoSections(Path dir) throws Exception {
        Path b = dir.resolve("book/Chap04/p001");
        Files.createDirectories(b);
        Files.writeString(b.resolve("p001.md"), "## Chapter 4 ▪ Objects and Classes\n\n## 4.1. Introduction\n\n## 4.1.1. Classes\n\n"
                + "A class specifies how objects are made.\n");
        String db = dir.resolve("db/books").toString();
        new BookStructure().readToc(db, "core", dir.resolve("book").toString());
        new BookStructure().cutSections(db, "core");
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            store.saveSectionRules("core", new BookStore.SectionRules("4.1.1", """
                    (defrule s1 -> p1 :heading "## 4.1.1. Classes")
                    (defrule p1 -> :nucleus p1/1 :example p1/2)
                    (defrule p1/1 -> "A class specifies how objects are made.")
                    (defrule p1/2 -> "A cookie cutter makes cookies.")
                    """, Map.of(), 2));
            store.saveSectionRules("core", new BookStore.SectionRules("4.1", "(defrule s1 -> :heading \"## 4.1\")", Map.of(), 0));
        }
        return db;
    }

    @Test
    void listAll_oneSectionThrows_failsNamingItAndKeepsTheOther(@TempDir Path dir) throws Exception {
        String db = bookWithTwoSections(dir);
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + db + ";AUTO_SERVER=TRUE")) {
            Exception failure = assertThrows(Exception.class, () -> SectionConcepts.listAll(store, "core", List.of(),
                    q -> { throw new IllegalStateException("model down"); }, 2, line -> { }));

            assertTrue(failure.getMessage().startsWith("1 of 2 failed: 4.1.1: error: java.lang.IllegalStateException: model down"),
                    failure.getMessage());
            assertTrue(failure.getMessage().endsWith(". 1 section(s) of core listed, 1 failed"), "the other section was done: " + failure.getMessage());
            assertFalse(store.listedConcepts("core").containsKey("4.1.1"), "the failed section is not saved, so a rerun asks it");
        }
    }

    @Test
    void identifyAll_oneNameThrows_failsAndKeepsThatNamesEarlierConceptsAndItems(@TempDir Path dir) throws Exception {
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            List<BookStore.FrameRule> rules = List.of(new BookStore.FrameRule(BookStore.FrameRule.PROPERTY, "b1/1", "A rule."));
            for (String number : List.of("6.1", "9.1")) {
                store.saveFrameConcepts("core", number, List.of(new BookStore.FrameConcept("interface", rules, true),
                        new BookStore.FrameConcept("class", rules, true)));
            }
            // an earlier run split "interface" into two concepts and gave the first one an item
            store.saveEntities("core", BookStore.Entity.CONCEPT, List.of(
                    new BookStore.Entity("interface#1", "interface", BookStore.Entity.CONCEPT,
                            List.of(new BookStore.Occurrence("6.1", "interface")), List.of()),
                    new BookStore.Entity("interface#2", "interface", BookStore.Entity.CONCEPT,
                            List.of(new BookStore.Occurrence("9.1", "interface")), List.of())));
            store.saveEntityItems("core", "interface#1", List.of(new BookStore.FrameItem("What is an interface?", List.of("6.1:b1/1"))));

            Exception failure = assertThrows(Exception.class, () -> GeneralFrames.identifyAll(store, "core", List.of(), q -> {
                if (q.contains("\"interface\"")) throw new IllegalStateException("model down");
                return "G: 1, 2";
            }, 2, line -> { }));

            assertTrue(failure.getMessage().startsWith("1 of 2 failed: interface: error: java.lang.IllegalStateException: model down"),
                    failure.getMessage());
            Map<String, BookStore.Entity> byId = store.entities("core").stream()
                    .collect(Collectors.toMap(BookStore.Entity::id, e -> e));
            assertEquals(List.of("class", "interface#1", "interface#2"), byId.keySet().stream().sorted().toList(),
                    "the failed name keeps its earlier concepts; the name that was judged is replaced");
            assertEquals(2, byId.get("class").members().size());
            assertEquals(List.of(new BookStore.FrameItem("What is an interface?", List.of("6.1:b1/1"))),
                    store.entityItems("core").get("interface#1"), "the earlier items of the failed name stay");
        }
    }

    @Test
    void identifyAll_nameFailsAndAnotherNameStartsWithItAndHash_keepsOnlyTheFailedNamesConcepts(@TempDir Path dir) throws Exception {
        try (BookStore store = BookStore.forWriting("jdbc:h2:" + dir.resolve("db/books") + ";AUTO_SERVER=TRUE")) {
            List<BookStore.FrameRule> rules = List.of(new BookStore.FrameRule(BookStore.FrameRule.PROPERTY, "b1/1", "A rule."));
            for (String number : List.of("6.1", "9.1")) {
                store.saveFrameConcepts("core", number, List.of(new BookStore.FrameConcept("C", rules, true),
                        new BookStore.FrameConcept("C#", rules, true)));
            }
            // an earlier run split "C" into c#1 and c#2, and kept "C#" as the one concept c#
            store.saveEntities("core", BookStore.Entity.CONCEPT, List.of(
                    new BookStore.Entity("c#1", "C", BookStore.Entity.CONCEPT, List.of(new BookStore.Occurrence("6.1", "C")), List.of()),
                    new BookStore.Entity("c#2", "C", BookStore.Entity.CONCEPT, List.of(new BookStore.Occurrence("9.1", "C")), List.of()),
                    new BookStore.Entity("c#", "C#", BookStore.Entity.CONCEPT,
                            List.of(new BookStore.Occurrence("6.1", "C#"), new BookStore.Occurrence("9.1", "C#")), List.of())));

            Exception failure = assertThrows(Exception.class, () -> GeneralFrames.identifyAll(store, "core", List.of(), q -> {
                if (q.contains("\"c\"")) throw new IllegalStateException("model down");
                return "G: 1, 2";
            }, 2, line -> { }));

            assertTrue(failure.getMessage().startsWith("1 of 2 failed: c: error: java.lang.IllegalStateException: model down"),
                    failure.getMessage());
            List<String> ids = store.entities("core").stream().map(BookStore.Entity::id).sorted().toList();
            assertEquals(List.of("c#", "c#1", "c#2"), ids,
                    "c keeps c#1 and c#2; c# is the judged name's new concept, stored once, not kept as c's");
        }
    }

    @Test
    void conceptsAction_oneSectionFails_isFalseWithTheSectionAndReason(@TempDir Path dir) throws Exception {
        String db = bookWithTwoSections(dir);
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "model not loaded".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (IOException e) {
                // the client went away
            }
        });
        server.start();
        IIActorSystem system = new IIActorSystem("batch-partial-failure-test");
        try {
            BookStructureIIAR books = new BookStructureIIAR("books", system);
            system.addIIActor(books);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";

            // ChatEndpoint tries 4 times with 5-20 s sleeps, so the failed section takes about 50 s
            ActionResult result = books.callByActionName("concepts",
                    new JSONObject().put("db", db).put("name", "core").put("url", url).put("model", "test-model")
                            .put("parallel", 2).toString());

            assertFalse(result.isSuccess(), result.getResult());
            assertTrue(result.getResult().contains("1 of 2 failed: 4.1.1: "), result.getResult());
            assertTrue(result.getResult().contains("HTTP 500: model not loaded"), result.getResult());
        } finally {
            system.terminate();
            server.stop(0);
        }
    }
}
