package com.devprep.ai.data;

import com.devprep.ai.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Repository
public class JobRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public JobRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc=jdbc; this.json=json; }
    public ObjectNode profile() {
        return jdbc.query("SELECT * FROM job_profiles WHERE id='master'", (rs,n)->document(rs.getString("payload_json"),"master",rs.getInt("revision"),rs.getString("updated_at")))
                .stream().findFirst().orElseGet(()->document("{\"title\":\"通用简历\",\"resume\":\"\",\"facts\":\"\",\"preferences\":\"\"}","master",-1,""));
    }
    @Transactional public ObjectNode saveProfile(ObjectNode data,int revision) {
        String now=Instant.now().toString();
        if(revision==-1) {
            try {jdbc.update("INSERT INTO job_profiles(id,payload_json,updated_at) VALUES('master',?,?)",json.write(data),now);}
            catch(org.springframework.dao.DuplicateKeyException ex) {throw conflict();}
        } else check(jdbc.update("UPDATE job_profiles SET payload_json=?,revision=revision+1,updated_at=? WHERE id='master' AND revision=?",json.write(data),now,revision));
        return profile();
    }
    public List<ObjectNode> jobs() {
        return jdbc.query("SELECT * FROM job_opportunities ORDER BY updated_at DESC",(rs,n)->document(rs.getString("payload_json"),rs.getString("id"),rs.getInt("revision"),rs.getString("updated_at")));
    }
    public ObjectNode job(String id) {
        return jdbc.query("SELECT * FROM job_opportunities WHERE id=?",(rs,n)->document(rs.getString("payload_json"),rs.getString("id"),rs.getInt("revision"),rs.getString("updated_at")),id)
                .stream().findFirst().orElseThrow(()->ApiException.notFound("job_not_found","找不到这个岗位。"));
    }
    @Transactional public ObjectNode saveJob(String id,ObjectNode data,int revision) {
        String now=Instant.now().toString();
        try {
            String key=sourceKey(data.path("url").asText());
            if(id==null) {id=UUID.randomUUID().toString();jdbc.update("INSERT INTO job_opportunities(id,source_key,payload_json,updated_at) VALUES(?,?,?,?)",id,key,json.write(data),now);
                event(id,"saved","收藏岗位","");
            } else check(jdbc.update("UPDATE job_opportunities SET source_key=?,payload_json=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",key,json.write(data),now,id,revision));
        } catch(org.springframework.dao.DuplicateKeyException ex) {throw new ApiException(409,"job_already_saved","这个岗位链接已收藏，请从岗位列表打开并更新JD。");}
        return job(id);
    }
    private static String sourceKey(String value) {
        if(value.isBlank())return null;
        try {var url=java.net.URI.create(value).normalize();String path=url.getRawPath().replaceAll("/+$","");
            String canonical=url.getHost().toLowerCase(Locale.ROOT)+path+(url.getRawQuery()==null?"":"?"+url.getRawQuery());
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(Exception ex){throw new IllegalArgumentException("Invalid source URL",ex);}
    }
    public record Material(String id,String job_id,JsonNode content,JsonNode inputs,String model,String parent_id,String created_at) {}
    private org.springframework.jdbc.core.RowMapper<Material> materialMapper() {
        return (rs,n)->new Material(rs.getString("id"),rs.getString("job_id"),json.readTree(rs.getString("payload_json")),json.readTree(rs.getString("inputs_json")),rs.getString("model"),rs.getString("parent_id"),rs.getString("created_at"));
    }
    public List<Material> materials(String job) {return jdbc.query("SELECT * FROM job_materials WHERE job_id=? ORDER BY seq DESC",materialMapper(),job);}
    public Material material(String job,String id) {
        return jdbc.query("SELECT * FROM job_materials WHERE job_id=? AND id=?",materialMapper(),job,id).stream().findFirst()
                .orElseThrow(()->ApiException.notFound("job_material_not_found","这个材料版本不属于当前岗位。"));
    }
    @Transactional public Material append(ObjectNode job,ObjectNode profile,JsonNode content,JsonNode inputs,String model,String parent) {
        // Lock the master before the job so a concurrent master edit cannot silently change the generation inputs.
        if(profile!=null) {
            var versions=jdbc.queryForList("SELECT revision FROM job_profiles WHERE id='master' FOR UPDATE",Integer.class);
            if(versions.size()!=1||versions.get(0)!=profile.path("revision").asInt()) throw conflict();
        }
        check(jdbc.update("UPDATE job_opportunities SET revision=revision+1,updated_at=? WHERE id=? AND revision=?",Instant.now().toString(),job.path("id").asText(),job.path("revision").asInt()));
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO job_materials(id,job_id,payload_json,inputs_json,model,parent_id,created_at) VALUES(?,?,?,?,?,?,?)",
                id,job.path("id").asText(),json.write(content),json.write(inputs),model,parent,Instant.now().toString());
        event(job.path("id").asText(),job.path("status").asText(),parent.isBlank()?"生成岗位专属材料":"另存手动修改版",id);
        return material(job.path("id").asText(),id);
    }
    @Transactional public ObjectNode status(String id,int revision,String status,String note,String material) {
        var job=job(id); job.put("status",status);
        if(!material.isBlank()) material(id,material);
        var saved=saveJob(id,clean(job),revision);event(id,status,note,material);return saved;
    }
    public List<Map<String,Object>> events(String job) {return jdbc.queryForList("SELECT status,note,material_id,created_at FROM job_events WHERE job_id=? ORDER BY seq DESC",job);}
    private void event(String job,String status,String note,String material) {jdbc.update("INSERT INTO job_events(job_id,status,note,material_id,created_at) VALUES(?,?,?,?,?)",job,status,note,material,Instant.now().toString());}
    public List<Map<String,Object>> attachments(String job,String material) {
        material(job,material);
        return jdbc.queryForList("SELECT id,filename,created_at FROM job_attachments WHERE material_id=? ORDER BY created_at",material);
    }
    @Transactional public void attach(String job,String material,String name,String base64) {
        material(job,material);
        // Serializes attachment count checks with all edits to this job.
        jdbc.queryForList("SELECT revision FROM job_opportunities WHERE id=? FOR UPDATE",job);
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM job_attachments WHERE material_id=?",Integer.class,material);
        if(count!=null&&count>=5)throw ApiException.badRequest("too_many_attachments","每个版本最多附加 5 个文件。");
        jdbc.update("INSERT INTO job_attachments(id,material_id,filename,content_base64,created_at) VALUES(?,?,?,?,?)",UUID.randomUUID().toString(),material,name,base64,Instant.now().toString());
    }
    public List<Map<String,Object>> attachmentData(String job,String material) {material(job,material);return jdbc.queryForList("SELECT filename,content_base64 FROM job_attachments WHERE material_id=? ORDER BY created_at",material);}
    public void removeAttachment(String job,String material,String id) {material(job,material);jdbc.update("DELETE FROM job_attachments WHERE id=? AND material_id=?",id,material);}
    @Transactional public void delete(String id,int revision) {check(jdbc.update("DELETE FROM job_opportunities WHERE id=? AND revision=?",id,revision));}
    public static ObjectNode clean(ObjectNode node) {ObjectNode copy=node.deepCopy();copy.remove(List.of("id","revision","updated_at"));return copy;}
    private ObjectNode document(String payload,String id,int revision,String updated) {ObjectNode node=(ObjectNode)json.readTree(payload);return node.put("id",id).put("revision",revision).put("updated_at",updated);}
    public static void revision(JsonNode node,int expected) {if(node.path("revision").asInt()!=expected)throw conflict();}
    private static void check(int changed) {if(changed!=1)throw conflict();}
    public static ApiException conflict() {return new ApiException(409,"job_changed","岗位或通用简历已更新，请重新载入后再操作；已有材料版本仍保留。");}
}
