package com.devprep.ai;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.JobRepository;
import com.devprep.ai.model.*;
import com.devprep.ai.service.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:jobs-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","devprep.local-learning-file=","devprep.algorithm-file=","devprep.agent-min-interval-millis=0"})
@AutoConfigureMockMvc
class JobWorkflowIntegrationTest {
    @Autowired JobService service;@Autowired JobRepository repository;@Autowired ResumeDocuments documents;@Autowired ObjectMapper mapper;@Autowired MockMvc mvc;
    @MockitoBean ModelSettingsService settings;@MockitoBean ModelGateway gateway;
    private static final String ORIGINAL="# 李同学\n本科，2026年毕业\n实现 Redis 缓存，完成本机压测与接口文档。";
    @BeforeEach void setup(){when(settings.load()).thenReturn(new ModelConfiguration("fixture-gpt","test",Map.of()));}
    private ObjectNode profile(){return service.saveProfile(mapper.createObjectNode().put("revision",repository.profile().path("revision").asInt()).put("title","通用简历").put("resume",ORIGINAL).put("facts","本人负责缓存逻辑").put("preferences","项目在前"));}
    private ObjectNode job(){return service.saveJob(null,mapper.createObjectNode().put("title","Java后端").put("company","示例公司").put("url","https://jobs.example.com/"+UUID.randomUUID()).put("jd","要求Java、Redis；Kafka为加分项"));}
    private ObjectNode action(ObjectNode job,ObjectNode profile){return mapper.createObjectNode().put("revision",job.path("revision").asInt()).put("profile_revision",profile.path("revision").asInt());}
    private ObjectNode output() throws Exception {return (ObjectNode)mapper.readTree("""
        {"markdown":"# 李同学\\n## 项目经历\\n实现 Redis 缓存，完成本机压测与接口文档。\\n## 教育背景\\n本科，2026年毕业",
         "greeting":"您好，想了解Java后端岗位，我实现过 Redis 缓存，可以提供项目细节。",
         "self_introduction":"我完成了缓存实现、本机压测与接口文档，希望交流相关技术实践。",
         "follow_up":"您好，想了解岗位目前的招聘进展。如需补充项目说明，我可以提供。",
         "strategy":"将缓存实现放在教育背景之前，缺少Kafka经历不写成已掌握。",
         "requirements":[{"requirement":"Redis","status":"有证据","source":"resume","evidence":"实现 Redis 缓存","advice":"突出本人实现"},{"requirement":"Kafka","status":"未体现","source":"resume","evidence":"","advice":"补充学习实践"}],
         "changes":[{"source":"resume","evidence":"实现 Redis 缓存","revised":"实现 Redis 缓存，完成本机压测与接口文档。","reason":"将交付放在前面"}],
         "gaps":["Kafka实践尚未体现"],"checks":["核对本机压测环境与职责边界"]}
        """);}
    private void reply(JsonNode output){when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply(output.toString(),List.of()));}
    @Test void masterIsImmutableAndEachJobGetsIndependentSnapshotsAndMessages() throws Exception {
        var master=profile();var first=job();var second=job();reply(output());
        var one=service.generate(first.path("id").asText(),action(first,master));var two=service.generate(second.path("id").asText(),action(second,master));
        assertEquals(master,repository.profile());assertNotEquals(one.id(),two.id());assertNotEquals(one.job_id(),two.job_id());
        assertEquals(ORIGINAL,one.inputs().path("profile").path("resume").asText());assertTrue(one.content().path("greeting").asText().contains("Redis"));
        assertThrows(ApiException.class,()->repository.material(second.path("id").asText(),one.id()));
        assertEquals("saved",repository.job(first.path("id").asText()).path("status").asText());
    }
    @Test void changingMasterDuringGenerationRejectsStaleOutputWithoutSavingIt() throws Exception {
        var master=profile();var job=job();var result=output();
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(call->{service.saveProfile(master.deepCopy().put("resume",ORIGINAL+"\n新增项目说明"));return new AgentReply(result.toString(),List.of());});
        assertEquals("job_changed",assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master))).getCode());
        assertTrue(repository.materials(job.path("id").asText()).isEmpty());assertEquals(job,repository.job(job.path("id").asText()));
    }
    @Test void changingJdDuringGenerationRejectsStaleOutput() throws Exception {
        var master=profile();var job=job();var result=output();
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(call->{service.saveJob(job.path("id").asText(),job.deepCopy().put("jd","新的Go岗位JD"));return new AgentReply(result.toString(),List.of());});
        assertEquals("job_changed",assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master))).getCode());
        assertTrue(repository.materials(job.path("id").asText()).isEmpty());
    }
    @Test void malformedOutputUnfoundedQuotesAndInventedNumbersNeverCreateMaterial() throws Exception {
        var master=profile();var job=job();var bad=output();bad.put("markdown",bad.path("markdown").asText()+"\n提升99%");reply(bad);
        assertEquals("invalid_job_output",assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master))).getCode());
        bad=output();((ObjectNode)bad.path("changes").get(0)).put("evidence","领导百万并发项目");reply(bad);
        assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master)));
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply("not json",List.of()));
        assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master)));
        assertTrue(repository.materials(job.path("id").asText()).isEmpty());
    }
    @Test void missingModelAndStaleRevisionFailBeforeCallingProvider() {
        var master=profile();var job=job();
        assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master).put("profile_revision",master.path("revision").asInt()+1)));
        when(settings.load()).thenReturn(ModelConfiguration.empty());
        assertEquals("model_not_configured",assertThrows(ApiException.class,()->service.generate(job.path("id").asText(),action(job,master))).getCode());verifyNoInteractions(gateway);
    }
    @Test void editingCreatesNewVersionPreservesInputsAndExportsExactVersionWithAttachments() throws Exception {
        var master=profile();var job=job();String id=job.path("id").asText();reply(output());var first=service.generate(id,action(job,master));
        var latest=repository.job(id);ObjectNode edited=((ObjectNode)first.content()).deepCopy().put("revision",latest.path("revision").asInt()).put("greeting","您好，附上个人项目说明。");
        var second=service.edit(id,first.id(),edited);assertEquals(first.inputs(),second.inputs());assertEquals(first.id(),second.parent_id());assertNotEquals(first.content().path("greeting"),second.content().path("greeting"));
        String encoded=Base64.getEncoder().encodeToString("缓存项目说明".getBytes(StandardCharsets.UTF_8));service.attach(id,second.id(),mapper.createObjectNode().put("filename","项目说明.txt").put("content",encoded));
        assertTrue(repository.attachments(id,first.id()).isEmpty());
        // Live title/JD may change, but old exports must keep their original job identity.
        service.saveJob(id,repository.job(id).put("title","新的岗位").put("jd","新的JD"));
        var response=mvc.perform(get("/api/jobs/"+id+"/materials/"+second.id()+"/export/zip")).andExpect(status().isOk()).andReturn().getResponse();
        Map<String,byte[]> files=new HashMap<>();try(var zip=new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()),StandardCharsets.UTF_8)){for(ZipEntry e;(e=zip.getNextEntry())!=null;)files.put(e.getName(),zip.readAllBytes());}
        assertTrue(files.containsKey("resume.docx"));assertTrue(new String(files.get("messages.txt"),StandardCharsets.UTF_8).contains("附上个人项目说明"));
        assertTrue(new String(files.get("job-and-version.json"),StandardCharsets.UTF_8).contains("Java后端"));assertFalse(new String(files.get("job-and-version.json"),StandardCharsets.UTF_8).contains("新的岗位"));
        assertEquals("缓存项目说明",new String(files.get("attachments/1-项目说明.txt"),StandardCharsets.UTF_8));
        assertTrue(documents.extract("a.docx",Base64.getEncoder().encodeToString(files.get("resume.docx"))).contains("李同学"));
    }
    @Test void statusRequiresExactJobMaterialAndDoesNotTreatDownloadAsSubmission() throws Exception {
        var master=profile();var job=job();var other=job();String id=job.path("id").asText();reply(output());var version=service.generate(id,action(job,master));
        var current=repository.job(id);ObjectNode input=mapper.createObjectNode().put("revision",current.path("revision").asInt()).put("status","submitted");
        assertThrows(ApiException.class,()->service.status(id,input));
        assertThrows(ApiException.class,()->service.status(other.path("id").asText(),input.deepCopy().put("revision",0).put("material_id",version.id())));
        mvc.perform(get("/api/jobs/"+id+"/materials/"+version.id()+"/export/messages")).andExpect(status().isOk());assertEquals("saved",repository.job(id).path("status").asText());
        service.status(id,input.put("material_id",version.id()).put("note","已收到官网确认邮件"));
        assertEquals("submitted",repository.job(id).path("status").asText());assertEquals(version.id(),repository.events(id).get(0).get("material_id"));
    }
    @Test void duplicateLinksUnsafeUrlsAndAttachmentPathsAreRejected() throws Exception {
        var job=job();assertEquals("job_already_saved",assertThrows(ApiException.class,()->service.saveJob(null,job.deepCopy().put("url",job.path("url").asText()+"/#draft"))).getCode());
        assertThrows(ApiException.class,()->service.saveJob(null,job.deepCopy().put("url","javascript:alert(1)")));
        var master=profile();reply(output());var version=service.generate(job.path("id").asText(),action(job,master));
        assertThrows(ApiException.class,()->service.attach(job.path("id").asText(),version.id(),mapper.createObjectNode().put("filename","../a.txt").put("content","YQ==")));
        mvc.perform(post("/api/jobs").header("Origin","https://evil.example").contentType("application/json").content(job.toString())).andExpect(status().isForbidden());
    }
}
