package com.example.aiagentlocal.dev;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String PLANNER = "You are a careful software-project planner for a small local coding model. " +
      "Reply ONLY with JSON shaped like {\"steps\":[{\"title\":\"...\",\"instruction\":\"...\"}]}. " +
      "Use 1-8 concrete implementation steps in dependency order. Each step should be small enough for a 3B model. " +
      "No markdown. No shell commands. Include project files and testing when relevant.";
    private static final String CODER = "You are a software implementation worker. Finish ONE given step. " +
      "Return ONLY valid JSON: {\"summary\":\"short result\",\"files\":[{\"path\":\"relative/path.ext\",\"content\":\"COMPLETE file content\"}]}. " +
      "Only create or update files necessary for this step. Use paths relative to workspace. " +
      "Provide full file contents, no diffs and no markdown fences. Follow the step precisely. " +
      "Use real source formatting: include line breaks and consistent indentation inside each file content string; never flatten code onto one line. " +
      "If the task is informational instead of coding, return files:[] and answer in summary. " +
      "Never output a shell command as an operation.";
    private final OllamaClient ollama;
    private final AttachmentService attachments;
    private final ObjectMapper mapper;
    private final AtomicReference<Path> workspace;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChatSession> chats = new ConcurrentHashMap<>();

    private static class ChatSession {
        String model;
        final List<Map<String,Object>> messages = new ArrayList<>();
        boolean active;
    }

    public AgentService(OllamaClient ollama, AttachmentService attachments, ObjectMapper mapper, @Value("${agent.workspace}") String workspace) throws Exception {
        this.ollama = ollama; this.attachments = attachments; this.mapper = mapper;
        Path initial = Paths.get(workspace).toAbsolutePath().normalize();
        Files.createDirectories(initial);
        this.workspace = new AtomicReference<>(initial);
    }
    public Path workspace() { return workspace.get(); }
    public Path setWorkspace(String value) throws Exception {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Workspace path is required");
        Path next = Paths.get(value).toAbsolutePath().normalize();
        Files.createDirectories(next);
        workspace.set(next);
        return next;
    }

    static class Run {
        String id, model, prompt;
        volatile String answer = "";
        Path workspace;
        int ctx;
        boolean approval, chatOnly;
        boolean think;
        String conversationId;
        List<String> attachmentIds = List.of();
        volatile TaskMetrics metrics = TaskMetrics.EMPTY;
        final AtomicBoolean stopped = new AtomicBoolean(false);
        final List<Map<String,String>> events = new CopyOnWriteArrayList<>();
        final List<SseEmitter> listeners = new CopyOnWriteArrayList<>();
        volatile Future<?> future;
        volatile boolean done;
        volatile List<Step> plan = List.of();
        volatile int completed;
        final ConcurrentMap<String,String> generatedFiles = new ConcurrentHashMap<>();
    }
    record Step(String title, String instruction) {}
    public record StartRequest(String model, String prompt, Integer contextSize, Boolean approveWrites, boolean chatOnly,
                               Boolean think, List<String> attachmentIds, String conversationId) {}

    public String start(StartRequest r) {
        if (r.model() == null || r.model().isBlank() || r.prompt() == null || r.prompt().isBlank())
            throw new IllegalArgumentException("Select a model and enter a task");
        Run run = new Run();
        run.id = UUID.randomUUID().toString(); run.model = r.model(); run.prompt = r.prompt();
        run.workspace = workspace();
        run.ctx = Math.max(1024, Math.min(32768, r.contextSize() == null ? 4096 : r.contextSize()));
        run.approval = Boolean.TRUE.equals(r.approveWrites());
        run.chatOnly = r.chatOnly();
        run.think = Boolean.TRUE.equals(r.think());
        if (run.chatOnly) {
            run.conversationId = r.conversationId() == null || r.conversationId().isBlank()
                    ? UUID.randomUUID().toString() : r.conversationId();
            ChatSession chat = chats.computeIfAbsent(run.conversationId, ignored -> new ChatSession());
            synchronized (chat) {
                if (chat.active) throw new IllegalStateException("Wait for the previous chat response to finish");
                if (chat.model != null && !chat.model.equals(run.model)) chat.messages.clear();
                chat.model = run.model;
                chat.active = true;
            }
        }
        run.attachmentIds = r.attachmentIds() == null ? List.of() : List.copyOf(r.attachmentIds());
        runs.put(run.id, run);
        log.info("Task={} model={} prompt={}", run.id, run.model, run.prompt.replaceAll("[\r\n]+", " "));
        run.future = pool.submit(() -> execute(run));
        return run.id;
    }
    public String conversationId(String id) { return require(id).conversationId; }
    public void clearConversation(String id) {
        ChatSession chat = chats.get(id);
        if (chat == null) return;
        synchronized (chat) {
            if (chat.active) throw new IllegalStateException("Wait for the current response to finish");
            chats.remove(id, chat);
        }
    }
    public SseEmitter subscribe(String id) {
        Run run = require(id);
        SseEmitter emitter = new SseEmitter(0L);
        synchronized (run) {
            for (Map<String,String> event: run.events) {
                try { emitter.send(SseEmitter.event().name(event.get("type")).data(event.get("message"))); }
                catch (Exception e) { emitter.complete(); return emitter; }
            }
            if (run.done) { emitter.complete(); return emitter; }
            run.listeners.add(emitter);
        }
        emitter.onCompletion(() -> run.listeners.remove(emitter));
        emitter.onTimeout(() -> run.listeners.remove(emitter));
        return emitter;
    }
    public void stop(String id) {
        Run run = require(id);
        run.stopped.set(true);
        if (run.future != null) run.future.cancel(true);
        emit(run, "status", "Stop requested. In-flight Ollama request is being interrupted.");
    }
    public Map<String,Object> status(String id) {
        Run r = require(id);
        return Map.of("id",r.id,"done",r.done,"stopped",r.stopped.get(),"completed",r.completed,
                      "total",r.plan.size());
    }
    private Run require(String id) {
        Run r = runs.get(id);
        if (r == null) throw new IllegalArgumentException("Unknown task id");
        return r;
    }
    public Map<String,String> codeFiles(String id) {
        Run run = require(id);
        Map<String,String> files = new TreeMap<>();
        run.generatedFiles.forEach((name, content) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (List.of(".java", ".js", ".jsx", ".ts", ".tsx", ".py", ".html", ".css", ".json", ".xml", ".yml", ".yaml", ".sql", ".sh", ".properties", ".kt", ".go", ".rs", ".c", ".cpp", ".h").stream().anyMatch(lower::endsWith)) files.put(name, content);
        });
        return files;
    }
    public boolean hasActiveTask() { return runs.values().stream().anyMatch(r -> !r.done); }
    public Map<String,String> generatedFiles(String id) { return new TreeMap<>(require(id).generatedFiles); }
    public String chatAnswer(String id) { return require(id).answer; }
    public Map<String, Object> metrics(String id) { return require(id).metrics.display(); }
    public String saveGeneratedFile(String id, String name) throws Exception {
        Run run = require(id);
        String content = run.generatedFiles.get(name);
        if (content == null) throw new IllegalArgumentException("Unknown generated file: " + name);
        Path requested = safePath(workspace(), name);
        Path saved = uniquePath(requested);
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return workspace().relativize(saved).toString().replace('\\', '/');
    }
    private void emit(Run r, String type, String message) {
        synchronized (r) {
            r.events.add(Map.of("type", type, "message", message));
            // Limit memory stored for reconnects; tokens remain visible in active browser.
            while (r.events.size() > 3500) r.events.remove(0);
            for (SseEmitter listener: r.listeners) {
                try { listener.send(SseEmitter.event().name(type).data(message)); }
                catch (Exception e) { r.listeners.remove(listener); listener.complete(); }
            }
        }
    }
    private void check(Run r) throws InterruptedException {
        if (r.stopped.get() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Task stopped");
    }
    private void execute(Run r) {
        try {
            if (r.chatOnly) {
                emit(r,"status","Chatting with " + r.model + "...");
                ChatSession chat = chats.get(r.conversationId);
                List<Map<String,Object>> messages;
                synchronized (chat) {
                    messages = new ArrayList<>(chat.messages);
                }
                String userPrompt = augmentedPrompt(r);
                int historyBudget = Math.max(2000, r.ctx * 2 - Math.min(userPrompt.length(), r.ctx));
                while (!messages.isEmpty() && historyLength(messages) > historyBudget) {
                    messages.remove(0);
                    if (!messages.isEmpty()) messages.remove(0);
                }
                Map<String,Object> userMessage = new LinkedHashMap<>();
                userMessage.put("role", "user"); userMessage.put("content", userPrompt);
                List<String> imageData = images(r);
                if (!imageData.isEmpty()) userMessage.put("images", imageData);
                messages.add(userMessage);
                OllamaReply reply = ollama.chatMessages(r.model,
                    "You are a helpful local assistant. Answer directly in readable Markdown. Use headings, lists and tables when they clarify the answer. Put complete source code in fenced code blocks with the correct language. Preserve code indentation and line breaks. Do not return JSON unless the user asks for JSON.",
                    messages, r.ctx, chunk -> emit(r,"token",chunk), r.stopped::get, false, r.think);
                r.answer = reply.text(); r.metrics = r.metrics.plus(reply.metrics());
                check(r);
                synchronized (chat) {
                    chat.messages.add(Map.of("role", "user", "content", userPrompt));
                    chat.messages.add(Map.of("role", "assistant", "content", r.answer));
                    while (chat.messages.size() > 12 || historyLength(chat.messages) > 32000) {
                        chat.messages.remove(0); chat.messages.remove(0);
                    }
                }
                int snippet = 0;
                java.util.regex.Matcher blocks = java.util.regex.Pattern.compile("```([\\w#+.-]*)\\s*\\r?\\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(r.answer);
                while (blocks.find()) {
                    String extension = switch (blocks.group(1).toLowerCase(Locale.ROOT)) {
                        case "java" -> ".java"; case "javascript", "js" -> ".js"; case "typescript", "ts" -> ".ts";
                        case "python", "py" -> ".py"; case "html" -> ".html"; case "css" -> ".css";
                        case "json" -> ".json"; case "xml" -> ".xml"; case "sql" -> ".sql"; case "bash", "sh" -> ".sh";
                        default -> ".txt";
                    };
                    r.generatedFiles.put("chat-snippet-" + (++snippet) + extension, blocks.group(2));
                }
                emit(r,"chatResult",r.answer);
                emit(r,"complete","Chat response complete.");
                return;
            }
            emit(r,"status","Planning task using " + r.model + "...");
            OllamaReply planReply = ollama.chat(r.model, PLANNER, "Task:\n" + augmentedPrompt(r), r.ctx,
                chunk -> emit(r,"token",chunk), r.stopped::get, true, r.think, images(r));
            r.metrics = r.metrics.plus(planReply.metrics());
            String planText = planReply.text();
            check(r);
            JsonNode parsed = parseJson(planText);
            JsonNode steps = parsed.path("steps");
            if (!steps.isArray() || steps.isEmpty()) throw new IllegalArgumentException("Planner did not produce a valid steps array");
            List<Step> plan = new ArrayList<>();
            for (JsonNode s: steps) {
                String instruction = s.path("instruction").asText("").trim();
                if (!instruction.isBlank()) plan.add(new Step(s.path("title").asText("Step"), instruction));
                if (plan.size() >= 8) break;
            }
            if (plan.isEmpty()) throw new IllegalArgumentException("Planner output contained no usable instructions");
            r.plan = List.copyOf(plan);
            emit(r,"plan",mapper.writeValueAsString(plan));
            log.info("Task={} planned {} steps", r.id, plan.size());
            List<String> summaries = new ArrayList<>();
            for (int i=0; i<plan.size(); i++) {
                check(r);
                Step step = plan.get(i);
                emit(r,"step","Step " + (i+1) + "/" + plan.size() + ": " + step.title());
                String context = fileManifest(r.workspace);
                String memory = String.join("\n", summaries);
                String input = "ORIGINAL TASK:\n" + truncate(r.prompt, 4500) + "\n\nCURRENT STEP:\n" + step.instruction() +
                   "\n\nWORKSPACE FILES:\n" + truncate(context, 3000) +
                   "\n\nCOMPACT COMPLETED-STEP MEMORY:\n" + truncate(memory, 1800) +
                   "\n\nRELEVANT EXISTING FILE CONTENTS:\n" + relevantFiles(r.workspace, step, 7000);
                OllamaReply stepReply = ollama.chat(r.model, CODER, input, r.ctx,
                    chunk -> emit(r,"token",chunk), r.stopped::get, true, r.think, images(r));
                r.metrics = r.metrics.plus(stepReply.metrics());
                String output = stepReply.text();
                check(r);
                JsonNode result = parseJson(output);
                JsonNode files = result.path("files");
                if (!files.isArray()) throw new IllegalArgumentException("Step output missing files array");
                int written=0;
                for (JsonNode f: files) {
                    check(r);
                    String name = f.path("path").asText("");
                    String content = f.path("content").asText(null);
                    if (content == null) throw new IllegalArgumentException("Missing content for " + name);
                    Path path = safePath(r.workspace, name);
                    r.generatedFiles.put(name, content);
                    if (!r.approval) {
                        emit(r,"file","PREVIEW ONLY: " + r.workspace.relativize(path));
                        continue;
                    }
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                    emit(r,"file","Saved " + r.workspace.relativize(path) + " (" + content.length() + " chars)");
                    written++;
                }
                String summary = truncate(result.path("summary").asText("Completed step"), 350);
                summaries.add((i+1) + ": " + summary + " (" + written + " written)");
                while (summaries.size() > 5) summaries.remove(0);
                r.completed = i+1;
                emit(r,"stepDone",summary);
                log.info("Task={} completed step={} written={}",r.id,i+1,written);
            }
            emit(r,"complete","Finished " + r.completed + " steps. Workspace: " + r.workspace);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            emit(r,"stopped","Stopped by user");
        } catch (Exception ex) {
            if (r.stopped.get()) emit(r,"stopped","Stopped by user");
            else {
                log.error("Task {} failed",r.id,ex);
                emit(r,"error",ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        } finally {
            if (r.chatOnly) {
                ChatSession chat = chats.get(r.conversationId);
                if (chat != null) synchronized (chat) { chat.active = false; }
            }
            r.done=true;
            emit(r,"end","Task ended");
            for (SseEmitter listener: r.listeners) listener.complete();
            r.listeners.clear();
        }
    }
    private static int historyLength(List<Map<String,Object>> messages) {
        return messages.stream().mapToInt(m -> String.valueOf(m.get("content")).length()).sum();
    }
    private List<String> images(Run run) { return attachments.find(run.attachmentIds).stream().map(AttachmentService.Attachment::imageBase64).filter(Objects::nonNull).toList(); }
    private String augmentedPrompt(Run run) {
        StringBuilder value = new StringBuilder(run.prompt);
        for (AttachmentService.Attachment attachment : attachments.find(run.attachmentIds)) {
            if (attachment.imageBase64() == null) value.append("\n\nATTACHMENT ").append(attachment.name()).append(":\n").append(attachment.context());
        }
        return value.toString();
    }
    private JsonNode parseJson(String raw) throws Exception {
        String s=raw.trim();
        if (s.startsWith("```")) s=s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        int a=s.indexOf('{'), b=s.lastIndexOf('}');
        if (a<0 || b<a) throw new IllegalArgumentException("Model returned no JSON object. Try a coder/instruct model or smaller prompt.");
        return mapper.readTree(s.substring(a,b+1));
    }
    private static String truncate(String s, int max) { return s.length() > max ? s.substring(0,max) + "\n[TRUNCATED]" : s; }
    private Path safePath(Path workspace, String name) throws Exception {
        if (name.isBlank() || name.contains("\\") || name.startsWith("/") || name.contains(":"))
            throw new SecurityException("Unsafe file path: " + name);
        Path p=workspace.resolve(name).normalize();
        if (!p.startsWith(workspace) || p.equals(workspace)) throw new SecurityException("Path outside workspace: " + name);
        // Reject symbolic links in existing path elements to prevent writing outside workspace.
        Path current=workspace;
        for (Path part: workspace.relativize(p)) {
            current=current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new SecurityException("Symbolic link path disallowed: " + name);
        }
        return p;
    }
    private Path uniquePath(Path requested) {
        if (!Files.exists(requested)) return requested;
        String file = requested.getFileName().toString(); int dot = file.lastIndexOf('.');
        String stem = dot < 0 ? file : file.substring(0, dot);
        String ext = dot < 0 ? "" : file.substring(dot);
        for (int n = 1; ; n++) {
            Path candidate = requested.resolveSibling(stem + n + ext);
            if (!Files.exists(candidate)) return candidate;
        }
    }
    private String fileManifest(Path workspace) throws Exception {
        StringBuilder b=new StringBuilder();
        try (var stream=Files.walk(workspace, 5)) {
            stream.filter(Files::isRegularFile).filter(p -> !Files.isSymbolicLink(p))
                .limit(100).forEach(p -> b.append(workspace.relativize(p)).append('\n'));
        }
        return b.toString();
    }
    private String relevantFiles(Path workspace, Step step, int budget) throws Exception {
        StringBuilder b=new StringBuilder();
        String instruction=step.instruction().toLowerCase(Locale.ROOT);
        try (var stream=Files.walk(workspace, 5)) {
            List<Path> paths=stream.filter(Files::isRegularFile).filter(p -> !Files.isSymbolicLink(p))
                .filter(p -> { try { return Files.size(p) < 15000; } catch (Exception e) { return false; } })
                .filter(p -> { String name=p.getFileName().toString().toLowerCase(Locale.ROOT);
                   return instruction.contains(name) || name.equals("pom.xml"); })
                .limit(6).toList();
            for (Path p: paths) {
                if (b.length()>=budget) break;
                b.append("\nFILE ").append(workspace.relativize(p)).append(":\n")
                 .append(truncate(Files.readString(p),Math.min(2500,budget-b.length()))).append('\n');
            }
        }
        return truncate(b.toString(),budget);
    }
}
