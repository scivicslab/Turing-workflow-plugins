package com.scivicslab.turingworkflow.plugins.logdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/**
 * log-serve listens beyond the loopback address only with mutual TLS (chat-98, TLS for log-serve).
 *
 * <p>The certificates are made for each run with the JDK's {@code keytool}: a server, a client the server trusts,
 * and an intruder it does not.</p>
 */
@Tag("LogServeTls_261123_oo01")
@DisplayName("log-serve - an address other than 127.0.0.1 needs mutual TLS")
@Timeout(180)
class LogServeTlsTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private Process child;

    private static final char[] PASSWORD = "changeit".toCharArray();

    @BeforeEach
    void makeCertificates() throws Exception {
        generate("server", "localhost");
        generate("client", "client1");
        generate("intruder", "intruder");
        trust("server-trust", "client");      // what the server trusts: the client
        trust("client-trust", "server");      // what the client trusts: the server
        trust("intruder-trust", "server");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (child != null) child.destroyForcibly();
    }

    private void keytool(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "keytool").toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", command) + "\n" + out).isZero();
    }

    private void generate(String name, String commonName) throws Exception {
        keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=" + commonName,
                "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", dir.resolve(name + ".p12").toString(), "-storepass", "changeit");
    }

    private void trust(String trustStore, String certificateOf) throws Exception {
        Path cer = dir.resolve(certificateOf + ".cer");
        if (!Files.exists(cer)) {
            keytool("-exportcert", "-alias", certificateOf, "-keystore", dir.resolve(certificateOf + ".p12").toString(),
                    "-storepass", "changeit", "-file", cer.toString());
        }
        keytool("-importcert", "-noprompt", "-alias", certificateOf, "-file", cer.toString(), "-storetype", "PKCS12",
                "-keystore", dir.resolve(trustStore + ".p12").toString(), "-storepass", "changeit");
    }

    private SSLContext context(String keys, String trusted) throws Exception {
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(dir.resolve(trusted + ".p12"))) {
            trustStore.load(in, PASSWORD);
        }
        tm.init(trustStore);
        KeyManagerFactory km = null;
        if (keys != null) {
            km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(dir.resolve(keys + ".p12"))) {
                keyStore.load(in, PASSWORD);
            }
            km.init(keyStore, PASSWORD);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(km == null ? null : km.getKeyManagers(), tm.getTrustManagers(), null);
        return ctx;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static boolean canConnect(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
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

    private int startViewer(SSLContext tls) throws Exception {
        int port = freePort();
        server = LogServeCLI.createHttpServer(InetAddress.getLoopbackAddress(), port, tls);
        server.createContext("/info", ex -> {
            byte[] body = "ok".getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        return port;
    }

    private static int status(SSLContext ctx, String url) throws Exception {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (ctx != null) b.sslContext(ctx);
        return b.build().send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("no TLS context for an address other than the loopback one fails before anything listens")
    void noContext_beyondLoopback_failsToStart() throws Exception {
        int port = freePort();

        assertThatThrownBy(() -> LogServeCLI.createHttpServer(InetAddress.getByName("0.0.0.0"), port, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mutual TLS");
        assertThat(canConnect(InetAddress.getLoopbackAddress(), port)).isFalse();
    }

    @Test
    @DisplayName("a client with a certificate the server trusts is served")
    void trustedClient_isServed() throws Exception {
        int port = startViewer(context("server", "server-trust"));

        assertThat(status(context("client", "client-trust"), "https://localhost:" + port + "/info")).isEqualTo(200);
    }

    @Test
    @DisplayName("a client with no certificate is not served")
    void clientWithoutCertificate_isRefused() throws Exception {
        int port = startViewer(context("server", "server-trust"));

        assertThatThrownBy(() -> status(context(null, "client-trust"), "https://localhost:" + port + "/info"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("a client whose certificate the server does not trust is not served")
    void untrustedClient_isRefused() throws Exception {
        int port = startViewer(context("server", "server-trust"));

        assertThatThrownBy(() -> status(context("intruder", "intruder-trust"), "https://localhost:" + port + "/info"))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("plain HTTP to the TLS port is not served")
    void plainHttp_isRefused() throws Exception {
        int port = startViewer(context("server", "server-trust"));

        assertThatThrownBy(() -> status(null, "http://localhost:" + port + "/info")).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("on the command line, a key store without a trust store is an error")
    void keyStoreAlone_isAnError() throws Exception {
        var options = new org.apache.commons.cli.Options();
        options.addOption(org.apache.commons.cli.Option.builder().longOpt("tls-keystore").hasArg().build());
        options.addOption(org.apache.commons.cli.Option.builder().longOpt("tls-truststore").hasArg().build());
        var cl = new org.apache.commons.cli.DefaultParser().parse(options,
                new String[] {"--tls-keystore", dir.resolve("server.p12").toString()});

        assertThatThrownBy(() -> LogServeCLI.sslContextFrom(cl)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("go together");
    }

    private void startProcess(List<String> extra, int http, int tcp) throws Exception {
        Path db = dir.resolve("workflow-log");
        try (H2LogStore ignored = new H2LogStore(db, false)) {
            // a log database as a workflow run leaves it
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), LogServeMain.class.getName(),
                "--db", db.toString(), "--http-port", "" + http, "--tcp-port", "" + tcp));
        command.addAll(extra);
        ProcessBuilder pb = new ProcessBuilder(command)
                .redirectOutput(dir.resolve("out.txt").toFile()).redirectError(dir.resolve("err.txt").toFile());
        pb.environment().put("LOGSERVE_PW", "changeit");
        child = pb.start();
    }

    @Test
    @DisplayName("--bind 0.0.0.0 without TLS exits with an error and listens on nothing")
    void process_bindWithoutTls_exitsWithError() throws Exception {
        int http = freePort(), tcp = freePort();

        startProcess(List.of("--bind", "0.0.0.0"), http, tcp);

        assertThat(child.waitFor(60, TimeUnit.SECONDS)).as("log-serve exits").isTrue();
        assertThat(child.exitValue()).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("err.txt"))).contains("mutual TLS");
        assertThat(canConnect(InetAddress.getLoopbackAddress(), http)).isFalse();
        assertThat(canConnect(InetAddress.getLoopbackAddress(), tcp)).isFalse();
    }

    @Test
    @DisplayName("--bind 0.0.0.0 with mutual TLS serves a trusted client over HTTPS, and starts no H2 TCP server")
    void process_bindWithTls_viewerOnly() throws Exception {
        InetAddress lan = lanAddress();
        assumeTrue(lan != null, "no non-loopback IPv4 address on this machine");
        int http = freePort(), tcp = freePort();

        startProcess(List.of("--bind", "0.0.0.0",
                "--tls-keystore", dir.resolve("server.p12").toString(), "--tls-keystore-password-env", "LOGSERVE_PW",
                "--tls-truststore", dir.resolve("server-trust.p12").toString(), "--tls-truststore-password-env", "LOGSERVE_PW"),
                http, tcp);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (!canConnect(lan, http)) {
            assertThat(child.isAlive()).as("log-serve is running: " + Files.readString(dir.resolve("err.txt"))).isTrue();
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(200);
        }

        assertThat(status(context("client", "client-trust"), "https://localhost:" + http + "/info")).isEqualTo(200);
        assertThat(canConnect(lan, tcp)).as("H2 TCP server on " + lan.getHostAddress()).isFalse();
        assertThat(canConnect(InetAddress.getLoopbackAddress(), tcp)).as("H2 TCP server on loopback").isFalse();
    }
}
