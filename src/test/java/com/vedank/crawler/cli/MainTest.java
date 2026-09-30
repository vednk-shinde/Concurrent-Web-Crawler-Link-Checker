package com.vedank.crawler.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    private HttpServer server;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body = switch (path) {
                case "/" -> "<a href='/fine'>ok</a> <a href='/broken'>bad</a>".getBytes(StandardCharsets.UTF_8);
                case "/clean" -> "<a href='/fine'>ok</a>".getBytes(StandardCharsets.UTF_8);
                case "/fine" -> "fine".getBytes(StandardCharsets.UTF_8);
                default -> null;
            };
            ex.getResponseHeaders().set("Content-Type", "text/html");
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private int run(String... args) {
        return Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void exitsOneWhenBrokenLinksAreFound(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("out.csv");
        int code = run(base() + "/", "--rps", "100", "--csv", csv.toString());

        assertEquals(1, code);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[HTTP 404] " + base() + "/broken"), output);
        assertTrue(Files.readString(csv).contains("/broken,CLIENT_ERROR,404"));
    }

    @Test
    void exitsZeroWhenEverythingIsFine() {
        assertEquals(0, run(base() + "/clean", "--rps", "100", "--quiet"));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("No broken links found."));
    }

    @Test
    void usageErrorsExitTwo() {
        assertEquals(2, run());
        assertEquals(2, run(base() + "/", "--bogus"));
        assertEquals(2, run(base() + "/", "--depth"));
        assertEquals(2, run(base() + "/", "--threads", "0"));
        assertEquals(2, run("not a url"));
        assertEquals(0, run("--help"));
    }
}
