package dev.jarvis.task;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client of the Agent2Agent protocol (A2A): reads an agent's card, sends it a message (JSON-RPC message/send) and,
 * when the agent works on it as a task, asks for it again (tasks/get) until it is done. The JDK HTTP client only.
 */
public final class A2aClient {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final Set<String> FINAL = Set.of("completed", "failed", "canceled", "cancelled", "rejected",
            "input-required", "auth-required", "unknown");
    private static final List<String> CARDS = List.of("/.well-known/agent-card.json", "/.well-known/agent.json");

    /** @param endpoint where its JSON-RPC answers; description and name from its card */
    public record Card(URI endpoint, String name, String description) {
    }

    private final String token;

    /** @param token sent as "Authorization: Bearer"; blank: none */
    public A2aClient(String token) {
        this.token = token == null ? "" : token.strip();
    }

    /** http(s) only: the address is the user's, and a request goes there. */
    public static URI address(String url) {
        URI uri = URI.create(url == null ? "" : url.strip());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("Not an http(s) address: " + url);
        }
        return uri;
    }

    /** The agent card at the address (or at its well-known path); empty if there is none. */
    public Optional<Card> card(URI address) throws IOException, InterruptedException {
        List<URI> candidates = address.getPath() != null && address.getPath().endsWith(".json") ? List.of(address)
                : CARDS.stream().map(path -> address.resolve(path)).toList();
        for (URI candidate : candidates) {
            HttpResponse<String> response = HTTP.send(request(candidate).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode card = JSON.readTree(response.body());
                String url = card.path("url").asString("");
                return Optional.of(new Card(url.isBlank() ? address : address(url), card.path("name").asString(""),
                        card.path("description").asString("")));
            }
        }
        return Optional.empty();
    }

    /** Sends the text and returns the agent's answer: its message, or the text of the task's artifacts. */
    public String send(URI endpoint, String text) throws IOException, InterruptedException {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "user").put("messageId", UUID.randomUUID().toString()).put("kind", "message");
        message.putArray("parts").addObject().put("kind", "text").put("text", text);
        ObjectNode params = JSON.createObjectNode();
        params.set("message", message);
        JsonNode result = call(endpoint, "message/send", params);
        while ("task".equals(result.path("kind").asString("")) && !FINAL.contains(state(result))) {
            Thread.sleep(POLL.toMillis());
            ObjectNode query = JSON.createObjectNode();
            query.put("id", result.path("id").asString(""));
            result = call(endpoint, "tasks/get", query);
        }
        if ("task".equals(result.path("kind").asString("")) && !"completed".equals(state(result))) {
            String said = texts(result.path("status").path("message"));
            throw new IOException("the agent ended with " + state(result) + (said.isBlank() ? "" : ": " + said));
        }
        return answer(result);
    }

    /** The text of a message, or of a task's artifacts and last status message. Pure logic. */
    static String answer(JsonNode result) {
        if (!"task".equals(result.path("kind").asString(""))) {
            return texts(result);
        }
        String artifacts = StreamSupport.stream(result.path("artifacts").spliterator(), false)
                .map(A2aClient::texts).filter(text -> !text.isBlank()).collect(Collectors.joining("\n\n"));
        return Stream.of(artifacts, texts(result.path("status").path("message")))
                .filter(text -> !text.isBlank()).collect(Collectors.joining("\n\n"));
    }

    private static String state(JsonNode task) {
        return task.path("status").path("state").asString("unknown");
    }

    /** The text parts of a message or artifact ("kind": "text"; older agents say "type"). */
    private static String texts(JsonNode holder) {
        return StreamSupport.stream(holder.path("parts").spliterator(), false)
                .filter(part -> "text".equals(part.path("kind").asString(part.path("type").asString(""))))
                .map(part -> part.path("text").asString(""))
                .collect(Collectors.joining("\n"));
    }

    private JsonNode call(URI endpoint, String method, JsonNode params) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0").put("id", UUID.randomUUID().toString()).put("method", method);
        body.set("params", params);
        HttpResponse<String> response = HTTP.send(request(endpoint).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        JsonNode reply = JSON.readTree(response.body());
        if (reply.has("error")) {
            throw new IOException(reply.path("error").path("message").asString("error"));
        }
        return reply.path("result");
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", "application/json");
        return token.isEmpty() ? request : request.header("Authorization", "Bearer " + token);
    }
}
