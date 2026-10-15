package com.devprep.ai.data;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

@Repository
public class InterviewRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public record Session(String id, JsonNode payload, int revision, String updated_at) { }
    public InterviewRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc=jdbc; this.json=json; }

    public List<Map<String,Object>> list() {
        return jdbc.query("SELECT id,title,role,status,revision,updated_at FROM interview_sessions ORDER BY updated_at DESC,seq DESC LIMIT 100",
                (rs,n)->Map.of("id",rs.getString("id"),"title",rs.getString("title"),"role",rs.getString("role"),
                        "status",rs.getString("status"),"revision",rs.getInt("revision"),"updated_at",rs.getString("updated_at")));
    }
    public Session require(String id) {
        return jdbc.query("SELECT id,payload_json,revision,updated_at FROM interview_sessions WHERE id=?",
                (rs,n)->new Session(rs.getString("id"),json.readTree(rs.getString("payload_json")),rs.getInt("revision"),rs.getString("updated_at")),id)
                .stream().findFirst().orElseThrow(()->ApiException.notFound("interview_not_found","找不到这场模拟面试。"));
    }
    public Session create(JsonNode payload) {
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        jdbc.update("INSERT INTO interview_sessions(id,title,role,status,payload_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                id,payload.path("title").asText(),payload.path("role").asText(),payload.path("status").asText(),json.write(payload),now,now);
        return require(id);
    }
    public Session update(Session before, JsonNode payload) {
        check(jdbc.update("UPDATE interview_sessions SET status=?,payload_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                payload.path("status").asText(),json.write(payload),Instant.now().toString(),before.id(),before.revision()));
        return require(before.id());
    }
    public void delete(String id,int revision) {require(id);check(jdbc.update("DELETE FROM interview_sessions WHERE id=? AND revision=?",id,revision));}
    public void revision(Session session,int expected) {if(session.revision()!=expected)conflict();}
    private static void check(int affected) {if(affected!=1)conflict();}
    private static void conflict() {throw new ApiException(409,"interview_changed","这场面试已在其他页面更新，请重新打开。当前回答草稿仍保留在本机。");}

    public List<JsonNode> knowledgeCandidates(List<String> keywords) {
        String filter=keywords.isEmpty()?"":" AND ("+String.join(" OR ",Collections.nCopies(keywords.size(),"LOWER(CONCAT(kp.domain,' ',kp.title,' ',q.stem)) LIKE ?"))+")";
        String sql="SELECT q.id,q.stem,q.options_json,q.explanation,q.answer_json,q.rubric_json,q.source_name,q.source_url,kp.title,kp.domain FROM questions q JOIN knowledge_points kp ON kp.id=q.knowledge_point_id WHERE LOWER(COALESCE(q.content_locale,q.source_locale,'')) LIKE 'zh%'"+filter+" ORDER BY RAND() LIMIT 60";
        List<JsonNode> found=jdbc.query(sql,(rs,n)->json.readTree(json.write(Map.of(
                "id",rs.getString("id"),"prompt",rs.getString("stem")+(json.strings(rs.getString("options_json")).isEmpty()?"":"\n\n"+String.join("\n",json.strings(rs.getString("options_json")))),"reference",rs.getString("explanation")+"\n"+rs.getString("answer_json"),
                "rubric",json.readTree(rs.getString("rubric_json")),"title",rs.getString("title"),"domain",rs.getString("domain"),
                "source_name",Objects.toString(rs.getString("source_name"),"本地题库"),"source_url",Objects.toString(rs.getString("source_url"),"")))),
                keywords.stream().map(k->"%"+k.toLowerCase(Locale.ROOT)+"%").toArray());
        if(found.isEmpty()&&!keywords.isEmpty())return knowledgeCandidates(List.of());
        return found.stream().filter(q->q.path("prompt").asText().matches("(?s).*[\\p{IsHan}].*")).toList();
    }
}
