package com.devprep.ai.api;

import com.devprep.ai.data.StudyRepository;
import com.devprep.ai.service.StudyAgentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

@RestController
@RequestMapping("/api/study")
public class StudyController {
    private final StudyRepository repository;
    private final StudyAgentService agent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    public StudyController(StudyRepository repository, StudyAgentService agent) { this.repository = repository; this.agent = agent; }
    @PreDestroy void close() { executor.shutdownNow(); }
    @GetMapping("/sessions") public Map<String, Object> sessions() { return Map.of("sessions", repository.sessions()); }
    @PostMapping("/sessions") public StudyRepository.Session create(@RequestBody Map<String, String> body) {
        String mode = body.getOrDefault("mode", "mentor");
        if (!List.of("mentor", "quiz", "interview", "algorithm").contains(mode)) throw ApiException.badRequest("invalid_mode", "辅导模式无效。");
        return repository.create(mode);
    }
    @GetMapping("/sessions/{id}") public Map<String, Object> detail(@PathVariable String id) { return Map.of("session", repository.require(id), "messages", repository.messages(id), "busy", agent.busy(id)); }
    @DeleteMapping("/sessions/{id}") public Map<String, Boolean> delete(@PathVariable String id) { if (agent.busy(id)) throw new ApiException(409,"session_busy","请先停止回答，再删除对话。"); repository.delete(id); return Map.of("ok", true); }
    @PatchMapping("/sessions/{id}") public StudyRepository.Session rename(@PathVariable String id, @RequestBody Map<String, String> body) { return repository.rename(id, body.get("title")); }
    @PostMapping(value="/sessions/{id}/messages", produces="text/event-stream")
    public SseEmitter send(@PathVariable String id, @RequestBody Map<String, String> body) {
        return respond(agent.begin(id, body.get("content"), false), id);
    }
    @PostMapping(value="/sessions/{id}/retry", produces="text/event-stream")
    public SseEmitter retry(@PathVariable String id) { return respond(agent.begin(id, null, true), id); }
    @PostMapping("/sessions/{id}/stop")
    public Map<String, Boolean> stop(@PathVariable String id) { repository.require(id); return Map.of("stopped",agent.stop(id)); }
    private SseEmitter respond(StudyAgentService.Turn turn, String id) {
        SseEmitter emitter = new SseEmitter(600_000L);
        emitter.onTimeout(() -> agent.stop(turn));
        emitter.onError(error -> agent.stop(turn));
        emitter.onCompletion(() -> agent.stop(turn));
        executor.submit(com.devprep.ai.auth.WorkspaceContext.capture(() -> {
            try {
                var message = agent.run(turn, (name, data) -> sendEvent(emitter, name, data));
                sendEvent(emitter, "message", message); emitter.complete();
            } catch (Exception ex) {
                try { sendEvent(emitter, "error", Map.of("message", ex instanceof ApiException api ? api.getMessage() : "本轮回答中断，请重试。")); }
                finally { emitter.complete(); }
            }
        }));
        return emitter;
    }
    private static void sendEvent(SseEmitter emitter, String name, Object data) {
        try { emitter.send(SseEmitter.event().name(name).data(data)); }
        catch (IOException ex) { throw new IllegalStateException("对话连接已关闭", ex); }
    }
}
