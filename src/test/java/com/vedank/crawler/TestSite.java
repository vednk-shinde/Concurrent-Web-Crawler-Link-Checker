package com.vedank.crawler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny in-process website for tests (JDK HttpServer, no network access
 * needed). Records every request so tests can assert on exactly what the
 * crawler fetched, how often, and how concurrently.
 */
final class TestSite implements AutoCloseable {

    interface Handler {
        Response handle(int hitNumber) throws Exception;
    }

    record Response(int status, String contentType, String body) {
        static Response html(String body) { return new Response(200, "text/html; charset=utf-8", body); }
        static Response status(int status) { return new Response(status, "text/html", "<h1>" + status + "</h1>"); }
    }

    private final HttpServer server;
    private final Map<String, Handler> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    private final List<Long> requestStartNanos = Collections.synchronizedList(new ArrayList<>());
    private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(32);

    TestSite() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    TestSite page(String path, String... linkTargets) {
        StringBuilder html = new StringBuilder("<html><body><h1>").append(path).append("</h1>");
        for (String target : linkTargets) {
            html.append("<a href=\"").append(target).append("\">").append(target).append("</a>\n");
        }
        html.append("</body></html>");
        String body = html.toString();
        return route(path, n -> Response.html(body));
    }

    TestSite route(String path, Handler handler) {
        routes.put(path, handler);
        return this;
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    Set<String> requestedPaths() {
        return new TreeSet<>(hits.keySet());
    }

    List<Long> requestStartNanos() {
        synchronized (requestStartNanos) {
            return new ArrayList<>(requestStartNanos);
        }
    }

    List<String> methods() {
        synchronized (methods) {
            return new ArrayList<>(methods);
        }
    }

    int maxConcurrentRequests() {
        return maxInFlight.get();
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        if (exchange.getRequestURI().getRawQuery() != null) path += "?" + exchange.getRequestURI().getRawQuery();
        int hit = hits.computeIfAbsent(path, p -> new AtomicInteger()).incrementAndGet();
        requestStartNanos.add(System.nanoTime());
        methods.add(exchange.getRequestMethod() + " " + path);
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            Handler handler = routes.get(path);
            Response response = handler == null ? Response.status(404) : handler.handle(hit);
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            if (response.status() >= 300 && response.status() < 400) {
                exchange.getResponseHeaders().set("Location", response.body());
                exchange.sendResponseHeaders(response.status(), -1);
            } else if ("HEAD".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
