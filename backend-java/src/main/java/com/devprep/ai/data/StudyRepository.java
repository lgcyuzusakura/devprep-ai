package com.devprep.ai.data;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class StudyRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public StudyRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }

    public record Session(String id, String title, String mode, String created_at, String updated_at) { }
    public record Message(String id, String role, String content, JsonNode payload, String created_at) { }

    public List<Session> sessions() {
        return jdbc.query("SELECT * FROM study_sessions ORDER BY updated_at DESC LIMIT 100",
                (rs, n) -> new Session(rs.getString("id"), rs.getString("title"), rs.getString("mode"), rs.getString("created_at"), rs.getString("updated_at")));
    }
    public Session require(String id) {
        return jdbc.query("SELECT * FROM study_sessions WHERE id=?",
                (rs, n) -> new Session(rs.getString("id"), rs.getString("title"), rs.getString("mode"), rs.getString("created_at"), rs.getString("updated_at")), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("session_not_found", "找不到这段对话。"));
    }
    public Session create(String mode) {
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        jdbc.update("INSERT INTO study_sessions(id,title,mode,created_at,updated_at) VALUES(?,?,?,?,?)", id, "新的学习对话", mode, now, now);
        return require(id);
    }
    public List<Message> messages(String id) {
        require(id);
        return jdbc.query("SELECT * FROM study_messages WHERE session_id=? ORDER BY seq", (rs, n) -> new Message(
                rs.getString("id"), rs.getString("role"), rs.getString("content"), json.readTree(rs.getString("payload_json")), rs.getString("created_at")), id);
    }
    @Transactional
    public Message append(String session, String role, String content, Object payload) {
        require(session);
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        jdbc.update("INSERT INTO study_messages(id,session_id,role,content,payload_json,created_at) VALUES(?,?,?,?,?,?)", id, session, role, content, json.write(payload), now);
        if ("user".equals(role)) jdbc.update("UPDATE study_sessions SET title=? WHERE id=? AND title='新的学习对话'", content.substring(0, Math.min(32, content.length())), session);
        jdbc.update("UPDATE study_sessions SET updated_at=? WHERE id=?", now, session);
        return new Message(id, role, content, json.readTree(json.write(payload)), now);
    }
    public void delete(String id) { require(id); jdbc.update("DELETE FROM study_sessions WHERE id=?", id); }

    public Session rename(String id, String title) {
        require(id);
        if (title == null || title.isBlank() || title.strip().length() > 80) throw ApiException.badRequest("invalid_title", "对话名称需要为 1–80 个字符。");
        jdbc.update("UPDATE study_sessions SET title=? WHERE id=?", title.strip(), id);
        return require(id);
    }

    @Transactional
    public void removeLastAssistant(String session) {
        var messages = messages(session);
        if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size()-1).role()))
            jdbc.update("DELETE FROM study_messages WHERE id=?", messages.get(messages.size()-1).id());
    }

    public List<Map<String, Object>> searchQuestions(String query) {
        String term = query.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbc.query("""
                SELECT q.id,q.stem,q.explanation,q.answer_json,k.title,k.domain,q.source_name,q.source_url
                FROM questions q JOIN knowledge_points k ON k.id=q.knowledge_point_id
                WHERE q.content_locale LIKE 'zh%' AND (LOWER(q.stem) LIKE LOWER(?) ESCAPE '!' OR LOWER(k.title) LIKE LOWER(?) ESCAPE '!')
                ORDER BY q.id LIMIT 5
                """, (rs, n) -> Map.of("id", rs.getString("id"), "title", rs.getString("title"), "domain", rs.getString("domain"),
                "stem", clip(rs.getString("stem"), 1800), "answer", clip(json.readTree(rs.getString("answer_json")).asText(), 3500),
                "explanation", clip(rs.getString("explanation"), 1800), "source_name", rs.getString("source_name") == null ? "内置资料" : rs.getString("source_name"),
                "source_url", rs.getString("source_url") == null ? "" : rs.getString("source_url")), "%" + term + "%", "%" + term + "%");
    }
    private static String clip(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }
}
