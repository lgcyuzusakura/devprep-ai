package com.devprep.ai;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.ResumeRepository;
import com.devprep.ai.model.*;
import com.devprep.ai.service.ResumeDocuments;
import com.devprep.ai.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:resume-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "devprep.local-learning-file=","devprep.algorithm-file=","devprep.agent-min-interval-millis=0"})
@AutoConfigureMockMvc
class ResumeWorkflowIntegrationTest {
    @Autowired ResumeService service;
    @Autowired ResumeRepository repository;
    @Autowired ResumeDocuments documents;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean ModelSettingsService settings;
    @MockitoBean ModelGateway gateway;
    private static final String ORIGINAL="张同学\n本科，2026年毕业\n订单项目：实现 Redis 缓存，压测接口延迟从200ms降低至120ms。";
    @BeforeEach void setup(){when(settings.load()).thenReturn(new ModelConfiguration("synthetic-gpt","test",Map.of()));}
    ObjectNode input(String jd){return mapper.createObjectNode().put("title","Java 校招").put("jd",jd).put("original",ORIGINAL).put("facts","").put("preferences","一页");}
    ObjectNode action(int revision){return mapper.createObjectNode().put("revision",revision).put("enhance",true);}
    ObjectNode analysis() throws Exception {return (ObjectNode)mapper.readTree("""
            {"positioning":"用缓存设计和性能验证支撑后端能力","requirements":[{"requirement":"Redis","priority":"核心","status":"有证据","evidence":"实现 Redis 缓存","source":"original","advice":"保留基线与验证方法"}],
             "strengths":["存在前后延迟数据"],"weaknesses":[{"issue":"测量环境缺失","action":"补充","reason":"需要口径"}],
             "questions":[{"id":"q1","question":"压测并发是多少？","why":"限定数据适用环境","example":"提供工具与负载"}],
             "enhancements":[{"claim":"完善热点缓存失效策略","assumptions":"本人参与实现","verification":"模拟并发热点请求","followup":"如何避免击穿"}],"outline":["教育","项目","技能"]}
            """);}
    ObjectNode rewrite() throws Exception {return (ObjectNode)mapper.readTree("""
            {"markdown":"# 张同学\\n本科，2026年毕业\\n## 项目\\n- 实现 Redis 缓存，将接口延迟从200ms降低至120ms。",
             "strategy":"先呈现个人实现与数据口径","changes":[{"original":"实现 Redis 缓存","revised":"实现 Redis 缓存，将接口延迟从200ms降低至120ms。","reason":"保留实现和可追问的数据","evidence":"实现 Redis 缓存，压测接口延迟从200ms降低至120ms。","source":"original"}],
             "omissions":[],"checks":["确认压测负载"],"missing_facts":["压测环境"]}
            """);}
    void reply(ObjectNode value){when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply(value.toString(),List.of()));}
    ResumeRepository.Workspace analyzed(String jd) throws Exception {var w=service.save(null,input(jd));reply(analysis());return service.analyze(w.id(),action(w.revision()));}

    @Test void ordinaryAndTargetedFlowsKeepSnapshotsVersionsAndManualExports() throws Exception {
        for(String jd:List.of("","Java岗位要求 Redis、MySQL")) {
            var w=analyzed(jd);reply(rewrite());var version=service.rewrite(w.id(),action(w.revision()));
            assertEquals(jd,version.inputs().path("jd").asText());assertEquals(ORIGINAL,version.inputs().path("original").asText());
            assertEquals(1,repository.versions(w.id()).size());assertEquals("synthetic-gpt",version.model());
            var current=repository.require(w.id());
            var edited=service.edit(w.id(),version.id(),action(current.revision()).put("markdown",version.markdown()+"\n新增800%待确认"));
            assertTrue(edited.report().path("manual_edit").asBoolean());assertTrue(edited.report().path("review_notes").toString().contains("800%"));
            assertEquals(2,repository.versions(w.id()).size());
            assertEquals(version.markdown(),repository.version(w.id(),version.id()).markdown());
            String extracted=documents.extract("resume.docx",Base64.getEncoder().encodeToString(documents.docx(edited.markdown())));
            assertTrue(extracted.contains("张同学"));assertTrue(extracted.contains("Redis"));
            repository.delete(w.id(),repository.require(w.id()).revision());
            assertThrows(ApiException.class,()->repository.version(w.id(),version.id()));
        }
    }
    @Test void inventedEvidenceAndUnprovidedNumbersAreRejectedWithoutSavingVersions() throws Exception {
        var w=analyzed("");ObjectNode value=rewrite();value.put("markdown",value.path("markdown").asText()+"\n性能提升99%。");reply(value);
        assertEquals("invalid_resume_output",assertThrows(ApiException.class,()->service.rewrite(w.id(),action(w.revision()))).getCode());
        assertTrue(repository.versions(w.id()).isEmpty());
        value=rewrite();((ObjectNode)value.path("changes").get(0)).put("evidence","主导百万并发");reply(value);
        assertThrows(ApiException.class,()->service.rewrite(w.id(),action(w.revision())));
        assertTrue(repository.versions(w.id()).isEmpty());
    }
    @Test void staleWritesAndStaleGenerationCannotOverwriteNewInputs() throws Exception {
        var w=analyzed("");var changed=input("新的JD").put("revision",w.revision());service.save(w.id(),changed);
        assertThrows(ApiException.class,()->service.save(w.id(),changed));
        assertThrows(ApiException.class,()->service.rewrite(w.id(),action(w.revision())));
        assertFalse(repository.require(w.id()).analysis().has("positioning"));
    }
    @Test void unconfiguredAndMalformedModelsDoNotProduceFakeSuccess() throws Exception {
        var w=service.save(null,input(""));when(settings.load()).thenReturn(ModelConfiguration.empty());
        assertEquals("model_not_configured",assertThrows(ApiException.class,()->service.analyze(w.id(),action(0))).getCode());
        verifyNoInteractions(gateway);
        when(settings.load()).thenReturn(new ModelConfiguration("test","test",Map.of()));
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply("not JSON",List.of()));
        assertEquals("invalid_resume_output",assertThrows(ApiException.class,()->service.analyze(w.id(),action(0))).getCode());
        assertFalse(repository.require(w.id()).analysis().has("positioning"));
    }
    @Test void enhancementIsOptionalAndAdoptedTextIsTracked() throws Exception {
        var w=service.save(null,input(""));reply(analysis());w=service.analyze(w.id(),action(0).put("enhance",false));
        assertTrue(w.analysis().path("enhancements").isEmpty());
        w=service.save(w.id(),input("").put("revision",w.revision()).put("facts","增强设想：补充缓存策略，待自行核验"));
        reply(rewrite());var v=service.rewrite(w.id(),action(w.revision()));
        assertTrue(v.report().path("review_notes").toString().contains("增强设想"));
    }
    @Test void documentsReadTextPdfAndWordAndRejectScannedOrOversizedFiles() throws Exception {
        assertEquals("中文简历",documents.extract("a.txt",Base64.getEncoder().encodeToString("中文简历".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        try(var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();pdf.addPage(page);
            try(var content=new PDPageContentStream(pdf,page)){content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);content.newLineAtOffset(50,700);content.showText("Resume: Java engineer");content.endText();}
            pdf.save(out);assertTrue(documents.extract("a.pdf",Base64.getEncoder().encodeToString(out.toByteArray())).contains("Java engineer"));
        }
        assertThrows(ApiException.class,()->documents.extract("a.txt","x".repeat(2_666_673)));
        assertThrows(ApiException.class,()->documents.extract("a.pdf",Base64.getEncoder().encodeToString("invalid".getBytes())));
    }
    @Test void endpointsValidateRevisionAndExportVersions() throws Exception {
        mvc.perform(post("/api/resumes").contentType("application/json").content("{\"title\":\"empty\"}")).andExpect(status().isBadRequest());
        var w=analyzed("");reply(rewrite());var v=service.rewrite(w.id(),action(w.revision()));
        mvc.perform(get("/api/resumes/"+w.id())).andExpect(status().isOk()).andExpect(jsonPath("$.versions[0].id").value(v.id()));
        mvc.perform(post("/api/resumes/"+w.id()+"/rewrite").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/resumes/"+w.id()+"/versions/"+v.id()+"/docx")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=resume.docx"));
    }
    @Test void independentDraftsKeepLayoutAndExpansionDoesNotOverwriteBody() throws Exception {
        var w=service.save(null,input(""));
        var layout=mapper.createObjectNode().put("template","compact").put("font","serif").put("size",12).put("density","compact").put("accent","blue");
        var payload=action(w.revision()).put("markdown","# 张同学\n## 项目\n实现 Redis 缓存");payload.set("layout",layout);
        var v=service.draft(w.id(),payload);assertEquals("compact",v.layout().path("template").asText());
        assertEquals("自主编写",v.model());
        w=repository.require(w.id());reply(mapper.createObjectNode().put("expanded","实现 Redis 缓存，增加回源路径。").put("reason","补充实现细节").set("checks",mapper.valueToTree(List.of("确认已实现"))));
        JsonNode expanded=service.expand(w.id(),action(w.revision()).put("selection","实现 Redis 缓存").put("instruction","说明实现"));
        assertTrue(expanded.path("expanded").asText().contains("回源"));assertEquals(1,repository.versions(w.id()).size());
        assertEquals(v.markdown(),repository.version(w.id(),v.id()).markdown());
        assertTrue(documents.extract("a.docx",Base64.getEncoder().encodeToString(documents.docx(v.markdown(),v.layout()))).contains("Redis"));
    }
}
