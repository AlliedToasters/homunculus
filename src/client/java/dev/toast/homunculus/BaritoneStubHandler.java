package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

// Fallback for /baritone/* contexts when baritone-api isn't on the runtime classpath.
// Pure Java, no baritone.api.* references — safe to instantiate without the API jar.
public final class BaritoneStubHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("reason", "baritone_not_loaded");
            body.put("message", "Baritone API not present at runtime");
            byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }
}
