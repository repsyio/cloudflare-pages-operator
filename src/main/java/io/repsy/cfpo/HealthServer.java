package io.repsy.cfpo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.RuntimeInfo;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Liveness and readiness endpoints for the Kubernetes probes. */
final class HealthServer {

  private static final int HTTP_OK = 200;
  private static final int HTTP_SERVICE_UNAVAILABLE = 503;

  private HealthServer() {}

  static HttpServer start(final int port, final Operator operator) throws IOException {
    final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/healthz", exchange -> respond(exchange, HTTP_OK, "ok"));
    server.createContext(
        "/readyz",
        exchange -> {
          final RuntimeInfo info = operator.getRuntimeInfo();
          final boolean ready = info.isStarted() && info.allEventSourcesAreHealthy();
          respond(
              exchange, ready ? HTTP_OK : HTTP_SERVICE_UNAVAILABLE, ready ? "ready" : "not ready");
        });
    server.start();
    return server;
  }

  private static void respond(final HttpExchange exchange, final int status, final String body)
      throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}
