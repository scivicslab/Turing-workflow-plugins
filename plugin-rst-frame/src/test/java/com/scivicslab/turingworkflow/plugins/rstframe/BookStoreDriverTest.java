package com.scivicslab.turingworkflow.plugins.rstframe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * BookStore opens its H2 database when DriverManager has no H2 driver registered, as happens when H2 is loaded by
 * another class loader (loader.loadJar or plugins.yaml).
 */
class BookStoreDriverTest {

    @TempDir
    Path dir;

    @AfterEach
    void registerAgain() {
        org.h2.Driver.load();
    }

    @Test
    void opensWithoutDriverManagerRegistration() throws Exception {
        org.h2.Driver.unload();

        try (BookStore store = new BookStore("jdbc:h2:" + dir.resolve("books"))) {
            assertEquals(0, store.books().size());
        }
    }
}
