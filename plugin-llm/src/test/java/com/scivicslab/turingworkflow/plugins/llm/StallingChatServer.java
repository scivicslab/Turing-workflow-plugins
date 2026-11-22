package com.scivicslab.turingworkflow.plugins.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for a chat-completions server that never finishes its answer: it either sends nothing at all, or
 * sends the headers and the first bytes of a JSON body and then stops while keeping the connection open.
 */
final class StallingChatServer implements AutoCloseable {

    /** Sends headers and part of a body, then nothing. */
    static final boolean STALL_INSIDE_BODY = true;

    /** Sends nothing at all. */
    static final boolean NEVER_ANSWER = false;

    private final ServerSocket server;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    final AtomicInteger requests = new AtomicInteger();

    StallingChatServer(boolean stallInsideBody) throws IOException {
        server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Socket socket = server.accept();
                    accepted.add(socket);
                    Thread.ofVirtual().start(() -> answer(socket, stallInsideBody));
                }
            } catch (IOException e) {
                // the server socket was closed
            }
        });
    }

    String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/v1/chat/completions";
    }

    String hostAndPort() {
        return "127.0.0.1:" + server.getLocalPort();
    }

    private void answer(Socket socket, boolean stallInsideBody) {
        try {
            readRequestHead(socket.getInputStream());
            requests.incrementAndGet();
            if (stallInsideBody) {
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n"
                        + "{\"choices\":[{\"message\":{\"content\":\"par").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException e) {
            // the client went away
        }
    }

    private static void readRequestHead(InputStream in) throws IOException {
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        int b;
        while (matched < 4 && (b = in.read()) != -1) {
            matched = (b == end[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket s : accepted) s.close();
    }
}
