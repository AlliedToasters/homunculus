package dev.toast.homunculus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** POST /shear/sheep. Body: {} — no parameters. Shears the nearest readyForShearing sheep
 *  within 3.5 blocks. Caller positions via /baritone/follow first. */
public final class ShearHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, Map.of("error", "method not allowed"));
                return;
            }

            Shearer.Result result;
            try {
                result = Shearer.shear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respond(exchange, 500, Map.of("error", "interrupted"));
                return;
            } catch (RuntimeException e) {
                HomunculusClient.LOGGER.error("shear/sheep failed", e);
                respond(exchange, 200, failureBody("internal_error", "shear/sheep threw: " + rootMessage(e)));
                return;
            }

            if (result instanceof Shearer.Ok ok) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", true);
                body.put("uuid", ok.uuid());
                body.put("color", ok.color());
                body.put("wool_dropped", ok.woolDropped());
                respond(exchange, 200, body);
            } else if (result instanceof Shearer.Failure f) {
                respond(exchange, 200, failureBody(f.reason(), f.message()));
            }
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> failureBody(String reason, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("reason", reason);
        body.put("message", message);
        return body;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
