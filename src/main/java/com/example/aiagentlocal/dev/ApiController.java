package com.example.aiagentlocal.dev;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.io.*;
import java.util.zip.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final OllamaClient ollama;
    private final AgentService agent;
    private final AttachmentService attachments;
    public ApiController(OllamaClient ollama, AgentService agent, AttachmentService attachments) { this.ollama=ollama; this.agent=agent; this.attachments=attachments; }
    @GetMapping("/models") public JsonNode models() throws Exception { return ollama.models(); }
    @GetMapping("/models/loaded") public JsonNode loadedModels() throws Exception { return ollama.loadedModels(); }
    @PostMapping("/models/load") public Map<String,String> load(@RequestBody Map<String,String> body) throws Exception {
        ollama.load(body.get("model")); return Map.of("status","Model loaded");
    }
    @PostMapping("/models/offload") public Map<String,String> offload(@RequestBody Map<String,String> body) throws Exception {
        ollama.offload(body.get("model")); return Map.of("status","Model unloaded");
    }
    @PostMapping("/tasks") public Map<String,String> start(@RequestBody AgentService.StartRequest r) {
        String id = agent.start(r);
        String conversationId = agent.conversationId(id);
        return conversationId == null ? Map.of("id", id) : Map.of("id", id, "conversationId", conversationId);
    }
    @DeleteMapping("/chats/{id}") public Map<String,String> clearChat(@PathVariable String id) {
        agent.clearConversation(id); return Map.of("status", "cleared");
    }
    @GetMapping(path="/tasks/{id}/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) { return agent.subscribe(id); }
    @GetMapping("/tasks/{id}") public Map<String,Object> status(@PathVariable String id) { return agent.status(id); }
    @GetMapping("/tasks/{id}/code") public Map<String,String> code(@PathVariable String id) { return agent.codeFiles(id); }
    @GetMapping("/tasks/{id}/files") public Map<String,String> files(@PathVariable String id) { return agent.generatedFiles(id); }
    @GetMapping("/tasks/{id}/chat") public Map<String,String> chat(@PathVariable String id) { return Map.of("answer",agent.chatAnswer(id)); }
    @GetMapping("/tasks/{id}/response") public ResponseEntity<byte[]> response(@PathVariable String id, @RequestParam(defaultValue="txt") String format) {
        String answer = agent.chatAnswer(id);
        String normalized = Set.of("txt", "md", "html").contains(format) ? format : "txt";
        String body = normalized.equals("html") ? "<!doctype html><meta charset=\"utf-8\"><pre>" + html(answer) + "</pre>" : answer;
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=response." + normalized)
                .contentType(normalized.equals("html") ? MediaType.TEXT_HTML : MediaType.TEXT_PLAIN)
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @GetMapping("/tasks/{id}/metrics") public Map<String,Object> metrics(@PathVariable String id) { return agent.metrics(id); }
    @PostMapping("/tasks/{id}/files/save") public Map<String,String> saveFile(@PathVariable String id, @RequestBody Map<String,String> body) throws Exception {
        return Map.of("path", agent.saveGeneratedFile(id, body.get("name")));
    }
    @PostMapping(path="/attachments", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,String> addAttachment(@RequestParam("file") MultipartFile file) throws Exception {
        AttachmentService.Attachment attachment = attachments.add(file);
        return Map.of("id", attachment.id(), "name", attachment.name(), "kind", attachment.imageBase64() == null ? "document" : "image");
    }
    @GetMapping("/tasks/{id}/export.zip") public ResponseEntity<byte[]> exportZip(@PathVariable String id) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : agent.generatedFiles(id).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-" + id + "-results.zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes.toByteArray());
    }
    @GetMapping("/tasks/{id}/code.zip") public ResponseEntity<byte[]> codeZip(@PathVariable String id) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : agent.codeFiles(id).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-" + id + "-code.zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).body(bytes.toByteArray());
    }
    @PostMapping("/tasks/{id}/stop") public Map<String,String> stop(@PathVariable String id) {
        agent.stop(id); return Map.of("status","stop requested");
    }
    @GetMapping("/workspace") public Map<String,String> workspace() { return Map.of("path",agent.workspace().toString()); }
    @PostMapping("/workspace") public Map<String,String> setWorkspace(@RequestBody Map<String,String> body) throws Exception {
        if (agent.hasActiveTask()) throw new IllegalStateException("Wait for the current task to finish before changing workspace");
        return Map.of("path",agent.setWorkspace(body.get("path")).toString());
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,String>> error(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?e.toString():e.getMessage()));
    }
    private static String html(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
