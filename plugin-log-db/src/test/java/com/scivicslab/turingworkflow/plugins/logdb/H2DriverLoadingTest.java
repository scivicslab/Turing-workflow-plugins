package com.scivicslab.turingworkflow.plugins.logdb;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way this plugin opens an H2 database works when DriverManager has no H2 driver registered, as happens when
 * H2 is loaded by another class loader (loader.loadJar or plugins.yaml).
 */
class H2DriverLoadingTest {

    @TempDir
    Path dir;

    @BeforeEach
    void unregisterH2() {
        org.h2.Driver.unload();
    }

    @AfterEach
    void registerAgain() {
        org.h2.Driver.load();
    }

    @Test
    void inMemoryStoreOpens() throws Exception {
        try (H2LogStore store = new H2LogStore()) {
            assertThat(store.getConnection().isClosed()).isFalse();
        }
    }

    @Test
    void fileStoreAndReaderOpen() throws Exception {
        Path db = dir.resolve("logs");
        try (H2LogStore store = new H2LogStore(db)) {
            assertThat(store.getConnection().isClosed()).isFalse();
        }
        H2LogReader reader = new H2LogReader(db);
        reader.close();
    }

    @Test
    void mergerOpens() throws Exception {
        try (Connection conn = LogMerger.open(dir.resolve("merged"), false)) {
            assertThat(conn.getAutoCommit()).isFalse();
        }
    }
}
