package com.homeease.tracking.controller;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal misbehaving Redis stand-in for failure-mode tests. It speaks just enough RESP to be a TCP peer.
 * Modes: reject every command with WRONGPASS (authentication failure), or accept and never answer (timeout).
 */
final class FakeRespServer implements Closeable {

    enum Mode { WRONGPASS, SILENT }

    private final ServerSocket server;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final Mode mode;
    private volatile boolean running = true;

    FakeRespServer(Mode mode) throws IOException {
        this.mode = mode;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::acceptLoop, "fake-resp-accept");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                clients.add(s);
                Thread t = new Thread(() -> serve(s), "fake-resp-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
            byte[] buf = new byte[4096];
            while (running && in.read(buf) > 0) {
                if (mode == Mode.WRONGPASS) {
                    out.write("-WRONGPASS invalid username-password pair or user is disabled.\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                // SILENT: read and say nothing
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
        for (Socket s : clients) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
