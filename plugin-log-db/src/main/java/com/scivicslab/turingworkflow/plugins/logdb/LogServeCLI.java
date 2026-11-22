/*
 * Copyright 2025 devteam@scivicslab.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.scivicslab.turingworkflow.plugins.logdb;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.h2.tools.Server;

import com.scivicslab.pluggablecli.CommandRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Starts an HTTP server that serves a web log viewer over an H2 log database and an optional H2 TCP server for shared
 * database access.
 *
 * <p>The HTTP server exposes:</p>
 * <ul>
 * <li>{@code /} — an HTML index listing recent sessions;</li>
 * <li>{@code /session?id=N} — an HTML view of the logs for session N;</li>
 * <li>{@code /info} — a liveness endpoint returning HTTP 200 (used by {@code db-clear} to detect a running log server
 * and refuse to delete a database that is in use).</li>
 * </ul>
 *
 * <p>The H2 TCP server (default port 29090) lets readers such as {@code log-search --server} connect to the same
 * database concurrently while it is being written.</p>
 *
 * <p><b>Who can reach it.</b> Both servers listen on {@code 127.0.0.1} unless {@code --bind <address>} names another
 * address, so by default only this machine can connect. The database has an empty password and the viewer has no
 * login, and neither has TLS: with another address, such as {@code 0.0.0.0} for every interface, any host that
 * reaches the HTTP port reads every logged line, and any host that reaches the H2 TCP port runs SQL, and with
 * {@code CREATE ALIAS} Java code, as the user running this command. A server started with such an address writes a
 * warning saying so. H2 takes its bind address once, when its classes are loaded, so the TCP server is started
 * only when H2 has not been loaded in this JVM with another address; run the command in a JVM of its own.</p>
 *
 * @author devteam@scivicslab.com
 * @since 1.5.0
 */
public class LogServeCLI {

    /** The address log-serve listens on unless {@code --bind} names another: this machine only. */
    static final String DEFAULT_BIND = "127.0.0.1";

    private static final String H2_BIND_PROPERTY = "h2.bindAddress";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    /**
     * Adds the {@code log-serve} command to the provided command repository.
     *
     * <p>The command allows starting an HTTP log viewer server and an optional H2 TCP server to provide web-based
     * access to the H2 log database.</p>
     *
     * @param repo the repository where the command is registered
     */
    public static void registerCommand(CommandRepository repo) {
        Options opts = new Options();
        opts.addOption(Option.builder()
                .longOpt("db")
                .hasArg(true).argName("path")
                .desc("H2 database path (without .mv.db extension)")
                .required(true)
                .build());
        opts.addOption(Option.builder()
                .longOpt("http-port")
                .hasArg(true).argName("port")
                .desc("HTTP port for the log viewer and /info endpoint (default: 29091)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("tcp-port")
                .hasArg(true).argName("port")
                .desc("H2 TCP server port for shared database access (default: 29090)")
                .build());
        opts.addOption(Option.builder()
                .longOpt("bind")
                .hasArg(true).argName("address")
                .desc("Address the HTTP viewer and the H2 TCP server listen on (default: 127.0.0.1, this machine only). "
                        + "Another address, such as 0.0.0.0 for every interface, lets other hosts read every log line and "
                        + "run SQL and Java as the user running this command: there is no TLS and no authentication")
                .build());
        opts.addOption(Option.builder()
                .longOpt("no-tcp")
                .desc("Do not start the H2 TCP server (HTTP viewer only)")
                .build());

        repo.addCommand("Log", "log-serve", opts,
                "Start an HTTP log viewer server (and H2 TCP server) over the log database.",
                cl -> new LogServeCLI().execute(cl));
    }

    /**
     * Starts an HTTP log viewer and an optional H2 TCP server to serve the log database.
     *
     * Blocks until the process is interrupted.
     *
     * @param cl the parsed command line containing the database path, HTTP port, and TCP port options
     */
    public void execute(CommandLine cl) {
        Path dbPath = Path.of(cl.getOptionValue("db")).toAbsolutePath();
        int httpPort = Integer.parseInt(cl.getOptionValue("http-port", "29091"));
        int tcpPort = Integer.parseInt(cl.getOptionValue("tcp-port", "29090"));
        boolean noTcp = cl.hasOption("no-tcp");
        String bind = cl.getOptionValue("bind", DEFAULT_BIND);
        InetAddress bindAddress;
        try {
            bindAddress = InetAddress.getByName(bind);
        } catch (java.net.UnknownHostException e) {
            System.err.println("Error: cannot resolve --bind " + bind + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        if (!bindAddress.isLoopbackAddress()) {
            System.err.println("Warning: log-serve listens on " + bind + ", which other hosts can reach. There is no TLS and"
                    + " no authentication: anyone who reaches the HTTP port can read every logged line, and anyone who"
                    + " reaches the H2 TCP port can run SQL and Java as the user running log-serve. Use the default"
                    + " (127.0.0.1) unless the readers are hosts on a network only they can reach.");
        }

        Server tcpServer = startTcpServer(noTcp, tcpPort, bind, bindAddress.isLoopbackAddress());

        HttpServer http;
        try {
            http = HttpServer.create(new InetSocketAddress(bindAddress, httpPort), 0);
        } catch (IOException e) {
            System.err.println("Error: failed to bind HTTP port " + httpPort + ": " + e.getMessage());
            if (tcpServer != null) {
                tcpServer.stop();
            }
            System.exit(1);
            return;
        }
        http.createContext("/info", ex -> handleInfo(ex, dbPath));
        http.createContext("/session", ex -> handleSession(ex, dbPath));
        http.createContext("/", ex -> handleIndex(ex, dbPath));
        http.setExecutor(null);
        http.start();

        System.out.println("Log database: " + dbPath);
        System.out.println("Log viewer:   http://" + (bindAddress.isAnyLocalAddress() ? "localhost" : bind) + ":" + httpPort + "/");
        System.out.println("Press Ctrl-C to stop.");

        CountDownLatch latch = new CountDownLatch(1);
        Server tcp = tcpServer;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            http.stop(0);
            if (tcp != null) {
                tcp.stop();
            }
            latch.countDown();
        }));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Starts the H2 TCP server on {@code bind}.
     *
     * <p>H2 takes the address it listens on from the system property {@code h2.bindAddress}, and reads it once,
     * when its classes are loaded. The property is set here before the first use of H2 by this command. If H2 was
     * loaded earlier in this JVM with another address, the server is not started: listening on more than was
     * asked for is the failure this guards against. {@code -tcpAllowOthers} is passed only for an address that
     * is not the loopback one.</p>
     */
    private Server startTcpServer(boolean noTcp, int tcpPort, String bind, boolean loopback) {
        if (noTcp) {
            return null;
        }
        try {
            if (System.getProperty(H2_BIND_PROPERTY) == null) {
                System.setProperty(H2_BIND_PROPERTY, bind);
            }
            String h2Bind = org.h2.engine.SysProperties.BIND_ADDRESS;
            if (!bind.equals(h2Bind)) {
                System.err.println("Warning: the H2 TCP server is not started: H2 listens on " + h2Bind
                        + " (" + H2_BIND_PROPERTY + ") and --bind asks for " + bind + ". Start log-serve in a JVM of"
                        + " its own, or set -D" + H2_BIND_PROPERTY + "=" + bind + ".");
                return null;
            }
            List<String> args = new java.util.ArrayList<>(List.of("-tcpPort", String.valueOf(tcpPort)));
            if (!loopback) {
                args.add("-tcpAllowOthers");
            }
            Server server = Server.createTcpServer(args.toArray(new String[0])).start();
            System.out.println("H2 TCP server started on " + bind + ":" + tcpPort);
            return server;
        } catch (Exception e) {
            System.err.println("Warning: failed to start H2 TCP server on port "
                    + tcpPort + ": " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // HTTP handlers
    // -----------------------------------------------------------------------

    private void handleInfo(HttpExchange ex, Path dbPath) throws IOException {
        String json = "{\"status\":\"ok\",\"db\":\"" + jsonEscape(dbPath.toString()) + "\"}";
        sendBytes(ex, 200, "application/json; charset=utf-8", json);
    }

    private void handleIndex(HttpExchange ex, Path dbPath) throws IOException {
        if (!"/".equals(ex.getRequestURI().getPath())) {
            sendBytes(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("<h1>Workflow Log Viewer</h1>");
        body.append("<p>Database: <code>").append(htmlEscape(dbPath.toString())).append("</code></p>");
        try (H2LogReader reader = new H2LogReader(dbPath)) {
            List<SessionSummary> sessions = reader.listSessions(50);
            if (sessions.isEmpty()) {
                body.append("<p>No sessions found.</p>");
            } else {
                body.append("<table><tr><th>Session</th><th>Workflow</th><th>Status</th>")
                        .append("<th>Started</th><th>Ended</th></tr>");
                for (SessionSummary s : sessions) {
                    body.append("<tr>")
                            .append("<td><a href=\"/session?id=").append(s.getSessionId())
                            .append("\">#").append(s.getSessionId()).append("</a></td>")
                            .append("<td>").append(htmlEscape(s.getWorkflowName())).append("</td>")
                            .append("<td>").append(htmlEscape(String.valueOf(s.getStatus()))).append("</td>")
                            .append("<td>").append(htmlEscape(formatTs(s.getStartedAt()))).append("</td>")
                            .append("<td>").append(htmlEscape(formatTs(s.getEndedAt()))).append("</td>")
                            .append("</tr>");
                }
                body.append("</table>");
            }
        } catch (Exception e) {
            body.append("<p class=\"err\">Failed to read database: ")
                    .append(htmlEscape(e.getMessage())).append("</p>");
        }
        sendBytes(ex, 200, "text/html; charset=utf-8", htmlPage("Log Viewer", body.toString()));
    }

    private void handleSession(HttpExchange ex, Path dbPath) throws IOException {
        long sessionId;
        try {
            sessionId = Long.parseLong(queryParam(ex, "id"));
        } catch (NumberFormatException e) {
            sendBytes(ex, 400, "text/plain; charset=utf-8", "Missing or invalid 'id' parameter");
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append("<p><a href=\"/\">&larr; sessions</a></p>");
        body.append("<h1>Session #").append(sessionId).append("</h1>");
        try (H2LogReader reader = new H2LogReader(dbPath)) {
            SessionSummary summary = reader.getSummary(sessionId);
            if (summary == null) {
                body.append("<p class=\"err\">Session not found.</p>");
            } else {
                body.append("<pre>").append(htmlEscape(summary.toString())).append("</pre>");
                List<LogEntry> logs = reader.getLogsByLevel(sessionId, LogLevel.DEBUG);
                body.append("<h2>Logs (").append(logs.size()).append(")</h2>");
                body.append("<table><tr><th>Time</th><th>Level</th><th>Node</th><th>Message</th></tr>");
                for (LogEntry entry : logs) {
                    body.append("<tr class=\"lvl-").append(entry.getLevel()).append("\">")
                            .append("<td>").append(htmlEscape(formatTs(entry.getTimestamp()))).append("</td>")
                            .append("<td>").append(entry.getLevel()).append("</td>")
                            .append("<td>").append(htmlEscape(entry.getNodeId())).append("</td>")
                            .append("<td>").append(htmlEscape(entry.getMessage())).append("</td>")
                            .append("</tr>");
                }
                body.append("</table>");
            }
        } catch (Exception e) {
            body.append("<p class=\"err\">Failed to read database: ")
                    .append(htmlEscape(e.getMessage())).append("</p>");
        }
        sendBytes(ex, 200, "text/html; charset=utf-8",
                htmlPage("Session #" + sessionId, body.toString()));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String htmlPage(String title, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>"
                + htmlEscape(title) + "</title><style>"
                + "body{font-family:system-ui,sans-serif;margin:2rem;}"
                + "table{border-collapse:collapse;width:100%;}"
                + "th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;font-size:13px;}"
                + "th{background:#f0f0f0;} code,pre{background:#f6f6f6;}"
                + ".err{color:#b00;} .lvl-ERROR{background:#fde8e8;} .lvl-WARN{background:#fff7e0;}"
                + "</style></head><body>" + body + "</body></html>";
    }

    private String queryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String formatTs(LocalDateTime ts) {
        return ts == null ? "N/A" : ts.atZone(ZONE).format(ISO);
    }

    private void sendBytes(HttpExchange ex, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String htmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
