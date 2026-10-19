package com.example.aiagentlocal.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

@Service
public class OllamaClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper mapper;
    private final String base;

    public OllamaClient(ObjectMapper mapper, @Value("${agent.ollama-url}") String base) {
        this.mapper = mapper;
        this.base = base.replaceAll("/+$", "");
    }

    public JsonNode models() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/tags"))
                .timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Ollama HTTP " + r.statusCode() + ": " + r.body());
        JsonNode models = mapper.readTree(r.body()).path("models");
        if (!models.isArray()) return mapper.createArrayNode();
        List<JsonNode> sorted = new ArrayList<>();
        models.forEach(sorted::add);
        sorted.sort(Comparator.comparing(n -> n.path("name").asText(), String.CASE_INSENSITIVE_ORDER));
        var result = mapper.createArrayNode();
        sorted.forEach(result::add);
        return result;
    }

    public JsonNode loadedModels() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/ps"))
                .timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Ollama HTTP " + r.statusCode() + ": " + r.body());
        return mapper.readTree(r.body()).path("models");
    }

    public void load(String model) throws Exception {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Choose a model to load");
        Map<String,Object> body = Map.of("model", model, "prompt", "", "stream", false,
                "keep_alive", -1, "options", Map.of("num_predict", 1));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/generate"))
                .timeout(Duration.ofMinutes(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Ollama HTTP " + r.statusCode() + ": " + r.body());
    }

    public void offload(String model) throws Exception {
        if (model == null || model.isBlank()) throw new IllegalArgumentException("Choose a model to offload");
        Map<String,Object> body = Map.of("model", model, "prompt", "", "keep_alive", 0);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/generate"))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Ollama HTTP " + r.statusCode() + ": " + r.body());
    }

    public OllamaReply chat(String model, String system, String user, int ctx, Consumer<String> token,
                       java.util.function.BooleanSupplier cancelled) throws Exception {
        return chat(model, system, user, ctx, token, cancelled, false, false, List.of());
    }

    public OllamaReply chat(String model, String system, String user, int ctx, Consumer<String> token,
                       java.util.function.BooleanSupplier cancelled, boolean jsonMode, boolean think, List<String> images) throws Exception {
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user"); userMessage.put("content", user);
        if (images != null && !images.isEmpty()) userMessage.put("images", images);
        return chatMessages(model, system, List.of(userMessage), ctx, token, cancelled, jsonMode, think);
    }

    public OllamaReply chatMessages(String model, String system, List<Map<String,Object>> messages, int ctx,
                       Consumer<String> token, java.util.function.BooleanSupplier cancelled, boolean jsonMode, boolean think) throws Exception {
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", true);
        payload.put("think", think);
        if (jsonMode) payload.put("format", "json");
        payload.put("keep_alive", -1);
        payload.put("options", Map.of("num_ctx", ctx, "temperature", 0.2));
        List<Map<String,Object>> conversation = new ArrayList<>();
        conversation.add(Map.of("role", "system", "content", system));
        conversation.addAll(messages);
        payload.put("messages", conversation);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofMinutes(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        HttpResponse<InputStream> response = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream stream = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Ollama HTTP " + response.statusCode() + ": " + new String(stream.readNBytes(3000)));
            }
            StringBuilder full = new StringBuilder(); TaskMetrics metrics = TaskMetrics.EMPTY;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Stopped by user");
                    if (line.isBlank()) continue;
                    JsonNode chunk = mapper.readTree(line);
                    if (chunk.hasNonNull("error")) throw new IOException(chunk.get("error").asText());
                    String value = chunk.path("message").path("content").asText("");
                    if (!value.isEmpty()) { full.append(value); token.accept(value); }
                    if (chunk.path("done").asBoolean()) {
                        metrics = new TaskMetrics(chunk.path("total_duration").asLong(), chunk.path("load_duration").asLong(),
                                chunk.path("prompt_eval_count").asLong(), chunk.path("prompt_eval_duration").asLong(),
                                chunk.path("eval_count").asLong(), chunk.path("eval_duration").asLong());
                        break;
                    }
                }
            }
            return new OllamaReply(full.toString(), metrics);
        }
    }
}
