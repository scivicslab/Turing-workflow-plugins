package com.scivicslab.turingworkflow.plugins.logdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * log-serve answers this machine only unless it is told another address (chat-86, HIGH 1).
 *
 * <p>Before the change its H2 TCP server was started with {@code -tcpAllowOthers} and listened on every
 * interface, and the database has an empty password: any host that reached the port connected as the
 * database's user without a password and, with {@code CREATE ALIAS}, ran a shell command as the user running
 * log-serve (chat-86's reproduction printed {@code uid=0(root)} on the pod). The HTTP viewer listened on every
 * interface as well, and shows every logged line without a login.</p>
 *
 * <p>Each case runs log-serve in a JVM of its own, because H2 reads its bind address once, when its classes are
 * loaded.</p>
 */
@Tag("LogServeBinding_261123_oo01")
@DisplayName("log-serve — listens on 127.0.0.1 unless told another address")
@Timeout(180)
class LogServeBindingTest {

    @TempDir
    Path dir;

    private Process child;

    @AfterEach
    void stopChild() {
        if (child != null) child.destroyForcibly();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static InetAddress lanAddress() throws Exception {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) continue;
            for (InetAddress a : Collections.list(nic.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a;
            }
        }
        return null;
    }

    private static boolean canConnect(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Starts log-serve over a fresh database and waits until its HTTP port answers on the loopback address. */
    private void start(int http, int tcp, String... extra) throws Exception {
        Path db = dir.resolve("workflow-log");
        try (H2LogStore ignored = new H2LogStore(db, false)) {
            // a log database as a workflow run leaves it; closed so that the server can open it
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LogServeMain.class.getName(),
                "--db", db.toString(), "--http-port", "" + http, "--tcp-port", "" + tcp));
        command.addAll(List.of(extra));
        child = new ProcessBuilder(command)
                .redirectOutput(dir.resolve("out.txt").toFile())
                .redirectError(dir.resolve("err.txt").toFile())
                .start();
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!canConnect(InetAddress.getByName("127.0.0.1"), http)) {
            assertThat(child.isAlive()).as("log-serve is running: " + Files.readString(dir.resolve("err.txt"))).isTrue();
            assertThat(System.nanoTime()).as("log-serve answers on its HTTP port").isLessThan(deadline);
            Thread.sleep(200);
        }
        // the TCP server starts before the HTTP one, so it is up now
    }

    private String errors() throws IOException {
        return Files.readString(dir.resolve("err.txt"));
    }

    @Test
    @DisplayName("by default the viewer and the H2 server answer the loopback address and no other")
    void byDefault_onlyTheLoopbackAddressIsReachable() throws Exception {
        InetAddress lan = lanAddress();
        assumeTrue(lan != null, "no non-loopback IPv4 address on this machine");
        int http = freePort(), tcp = freePort();

        start(http, tcp);

        assertThat(canConnect(InetAddress.getByName("127.0.0.1"), tcp)).as("H2 server on loopback").isTrue();
        assertThat(canConnect(lan, http)).as("HTTP viewer on " + lan.getHostAddress()).isFalse();
        assertThat(canConnect(lan, tcp)).as("H2 server on " + lan.getHostAddress()).isFalse();
        assertThat(errors()).as("a loopback server warns of nothing").doesNotContain("no TLS");
    }

    @Test
    @DisplayName("on the loopback address the H2 server still serves a local reader")
    void byDefault_aLocalReaderConnects() throws Exception {
        int http = freePort(), tcp = freePort();
        start(http, tcp);

        String url = "jdbc:h2:tcp://127.0.0.1:" + tcp + "/" + dir.resolve("workflow-log");
        try (Connection c = DriverManager.getConnection(url, "", "");
             ResultSet rs = c.createStatement().executeQuery("SELECT 1")) {
            assertThat(rs.next()).isTrue();
        }
    }
}
