package com.devprep.ai.data;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class ResumeRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public ResumeRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc=jdbc; this.json=json; }
    public record Workspace(String id, String title, String jd, String original, String facts, String preferences,
                            JsonNode analysis, int revision, String updated_at) { }
    public record Version(String id, String markdown, JsonNode report, JsonNode inputs, JsonNode layout, String model, String created_at) { }
    public List<Workspace> list() {
        // List entries contain no resume/contact text.
        return jdbc.query("SELECT id,title,revision,updated_at FROM resume_workspaces ORDER BY updated_at DESC LIMIT 100",
                (rs,n)->new Workspace(rs.getString("id"),rs.getString("title"),"","","","",json.readTree("{}"),rs.getInt("revision"),rs.getString("updated_at")));
    }
    public Workspace require(String id) {
        return jdbc.query("SELECT * FROM resume_workspaces WHERE id=?",(rs,n)->new Workspace(rs.getString("id"),rs.getString("title"),
                rs.getString("jd"),rs.getString("original_text"),rs.getString("facts"),rs.getString("preferences"),json.readTree(rs.getString("analysis_json")),
                rs.getInt("revision"),rs.getString("updated_at")),id).stream().findFirst().orElseThrow(()->ApiException.notFound("resume_not_found","找不到这份简历工作稿。"));
    }
    public Workspace create(String title,String jd,String original,String facts,String preferences) {
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        jdbc.update("INSERT INTO resume_workspaces(id,title,jd,original_text,facts,preferences,analysis_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,title,jd,original,facts,preferences,"{}",now,now);
        return require(id);
    }
    @Transactional public Workspace update(String id,int revision,String title,String jd,String original,String facts,String preferences) {
        Workspace before=require(id);
        String analysis=before.jd().equals(jd)&&before.original().equals(original)?json.write(before.analysis()):"{}";
        check(jdbc.update("UPDATE resume_workspaces SET title=?,jd=?,original_text=?,facts=?,preferences=?,analysis_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                title,jd,original,facts,preferences,analysis,Instant.now().toString(),id,revision));
        return require(id);
    }
    public Workspace analysis(String id,int revision,JsonNode result) {
        check(jdbc.update("UPDATE resume_workspaces SET analysis_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",json.write(result),Instant.now().toString(),id,revision));
        return require(id);
    }
    @Transactional public Version append(Workspace workspace,String markdown,JsonNode report,String model,JsonNode layout) {
        check(jdbc.update("UPDATE resume_workspaces SET revision=revision+1,updated_at=? WHERE id=? AND revision=?",Instant.now().toString(),workspace.id(),workspace.revision()));
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO resume_versions(id,workspace_id,markdown,report_json,input_json,layout_json,model,created_at) VALUES(?,?,?,?,?,?,?,?)",
                id,workspace.id(),markdown,json.write(report),json.write(workspace),json.write(layout),model,Instant.now().toString());
        return version(workspace.id(),id);
    }
    public List<Version> versions(String id) {
        require(id);
        return jdbc.query("SELECT * FROM resume_versions WHERE workspace_id=? ORDER BY seq DESC LIMIT 100",(rs,n)->new Version(rs.getString("id"),rs.getString("markdown"),
                json.readTree(rs.getString("report_json")),json.readTree(rs.getString("input_json")),json.readTree(rs.getString("layout_json")),rs.getString("model"),rs.getString("created_at")),id);
    }
    public Version version(String workspace,String id) {
        return jdbc.query("SELECT * FROM resume_versions WHERE workspace_id=? AND id=?",(rs,n)->new Version(rs.getString("id"),rs.getString("markdown"),
                json.readTree(rs.getString("report_json")),json.readTree(rs.getString("input_json")),json.readTree(rs.getString("layout_json")),rs.getString("model"),rs.getString("created_at")),workspace,id)
                .stream().findFirst().orElseThrow(()->ApiException.notFound("resume_version_not_found","找不到这个简历版本。"));
    }
    public void delete(String id,int revision) { require(id);check(jdbc.update("DELETE FROM resume_workspaces WHERE id=? AND revision=?",id,revision)); }
    public void revision(Workspace workspace,int expected) { if(workspace.revision()!=expected) conflict(); }
    private static void check(int affected) { if(affected!=1) conflict(); }
    private static void conflict() { throw new ApiException(409,"resume_changed","工作稿已在其他页面更新，请重新打开后再操作。原有版本已保留。"); }
}
