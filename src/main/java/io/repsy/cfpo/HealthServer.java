package io.repsy.cfpo;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.RuntimeInfo;

/** Liveness and readiness endpoints for the Kubernetes probes. */
final class HealthServer {

  private HealthServer() {}

  static HttpServer start(int port, Operator operator) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/healthz", exchange -> respond(exchange, 200, "ok"));
    server.createContext(
        "/readyz",
        exchange -> {
          RuntimeInfo info = operator.getRuntimeInfo();
          boolean ready = info.isStarted() && info.allEventSourcesAreHealthy();
          respond(exchange, ready ? 200 : 503, ready ? "ready" : "not ready");
        });
    server.start();
    return server;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
