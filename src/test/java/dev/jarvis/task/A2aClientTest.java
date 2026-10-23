package dev.jarvis.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** An A2A agent on loopback: its card, a task that works for a while, then its artifact. */
class A2aClientTest {

    @Test
    void readsTheCardSendsTheMessageAndWaitsForTheTask() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/agent-card.json", exchange -> reply(exchange, 200,
                "{\"name\":\"Meteo\",\"description\":\"Previsioni del tempo\",\"url\":\"" + base + "/rpc\"}"));
        server.createContext("/rpc", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"message/send\"")) {
                reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"task\",\"id\":\"t1\","
                        + "\"status\":{\"state\":\"working\"}}}");
            } else {
                boolean done = polls.incrementAndGet() > 1;
                reply(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"kind\":\"task\",\"id\":\"t1\","
                        + "\"status\":{\"state\":\"" + (done ? "completed" : "working") + "\"},"
                        + "\"artifacts\":[{\"parts\":[{\"kind\":\"text\",\"text\":\"Sole a Milano.\"}]}]}}");
            }
        });
        server.start();
        try {
            A2aClient client = new A2aClient("");
            A2aClient.Card card = client.card(URI.create(base)).orElseThrow();
            assertThat(card.description()).isEqualTo("Previsioni del tempo");
            assertThat(card.endpoint().getPath()).isEqualTo("/rpc");
            assertThat(client.send(card.endpoint(), "Che tempo fa a Milano?")).isEqualTo("Sole a Milano.");
            assertThat(polls.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onlyHttpAddresses() {
        assertThatThrownBy(() -> A2aClient.address("file:///etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThat(A2aClient.address(" https://agent.example.com ").getHost()).isEqualTo("agent.example.com");
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
