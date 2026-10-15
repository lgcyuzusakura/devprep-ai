package com.devprep.ai;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.InterviewRepository;
import com.devprep.ai.model.*;
import com.devprep.ai.service.InterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:interview-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "devprep.local-learning-file=","devprep.algorithm-file=","devprep.agent-min-interval-millis=0"})
@AutoConfigureMockMvc
class InterviewWorkflowIntegrationTest {
    @Autowired InterviewService service;
    @Autowired InterviewRepository repository;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean ModelSettingsService settings;
    @MockitoBean ModelGateway gateway;
    @BeforeEach void setup() {when(settings.load()).thenReturn(new ModelConfiguration("fixture-gpt","test",Map.of()));}
    ObjectNode setupInput() {ObjectNode input=mapper.createObjectNode().put("role","Java 后端").put("jd","Java校招，缓存与数据一致性").put("resume","本人开发课程订单系统").put("mode","bank").put("question_count",3).put("intensity","gentle");input.putArray("categories").add("knowledge").add("algorithm").add("project");return input;}
    ObjectNode action(JsonNode session) {return mapper.createObjectNode().put("revision",session.path("revision").asInt()).put("turn_id",session.path("turns").get(session.path("turns").size()-1).path("id").asText()).put("answer","用唯一约束保护重复请求，并测试并发下的状态变化。");}
    ObjectNode grade(boolean followup) {
        ObjectNode result=mapper.createObjectNode();ObjectNode dims=result.putObject("dimensions");
        dims.putObject("correctness").put("score",30).put("reason","有一致性控制思路");dims.putObject("depth").put("score",18).put("reason","缺少失败时序");
        dims.putObject("clarity").put("score",12).put("reason","结论清楚");dims.putObject("engineering").put("score",15).put("reason","提出测试但需要说明负载");
        result.put("feedback","说明失败路径和验证方法会更完整。").put("sample_answer","先定义重复请求，再说明唯一约束、事务边界和异常处理。").put("evidence","唯一约束");
        result.putArray("strengths").add("指出唯一约束");result.putArray("gaps").add("缺少失败时序和测试负载");
        if(followup) {ObjectNode q=result.putObject("followup").put("title","重复请求失败路径").put("prompt","唯一约束冲突后，如何恢复响应并避免再次扣减库存？").put("reference","解释事务与幂等记录的状态。");q.putArray("rubric").add("说明异常恢复").add("保证副作用幂等");}else result.putNull("followup");
        return result;
    }
    void reply(JsonNode result) {when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply(result.toString(),List.of()));}

    @Test void bankStartIsOfflineChineseAndNeverLeaksFutureQuestionsOrAnswers() {
        var session=service.start(setupInput());assertEquals(1,session.path("turns").size());
        assertEquals(3,repository.require(session.path("id").asText()).payload().path("questions").size());
        assertFalse(session.toString().contains("\"rubric\""));assertFalse(session.toString().contains("\"reference\""));
        assertTrue(session.path("turns").get(0).path("prompt").asText().matches("(?s).*[\\p{IsHan}].*"));
        verifyNoInteractions(gateway,settings);
    }
    @Test void selectedDifficultyControlsAlgorithmPoolAndSourceQuestionsWithoutDuplicates() {
        for(String difficulty:List.of("basic","intermediate","advanced")) {
            var input=setupInput().put("difficulty",difficulty).put("company_style","large").put("question_count",3);
            var session=service.start(input);var questions=repository.require(session.path("id").asText()).payload().path("questions");
            Set<String> prompts=new HashSet<>();
            for(JsonNode q:questions){assertEquals(difficulty,q.path("difficulty").asText());assertTrue(prompts.add(q.path("prompt").asText()));}
            var algorithm=questions.get(1);
            assertTrue((difficulty.equals("basic")?List.of("简单","easy"):difficulty.equals("intermediate")?List.of("中等","medium"):List.of("中等","medium","困难","hard")).contains(algorithm.path("algorithm_difficulty").asText().toLowerCase(Locale.ROOT)));
        }
        verifyNoInteractions(gateway);
    }
    @Test void difficultyStandardsReachGradingAndDoNotChangeWithFollowupIntensity() throws Exception {
        var session=service.start(setupInput().put("difficulty","basic").put("company_style","large").put("intensity","deep"));reply(grade(true));
        var result=service.answer(session.path("id").asText(),action(session));
        var context=org.mockito.ArgumentCaptor.forClass(List.class);verify(gateway).agentTurn(any(),anyString(),context.capture(),anyList());
        var sent=mapper.readTree(((Map<?,?>)context.getValue().get(0)).get("content").toString());
        assertEquals("basic",sent.path("config").path("difficulty_resolved").asText());
        assertTrue(sent.path("config").path("difficulty_standard").path("criteria").asText().contains("不要求生产经验"));
        assertEquals("basic",result.path("turns").get(1).path("difficulty").asText());
    }
    @Test void jdRetrievalCitationsAreValidatedAndExposeProvenanceWithoutReferenceAnswers() {
        var input=setupInput().put("mode","ai");
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(call->{
            JsonNode context=mapper.readTree(((List<Map<String,Object>>)call.getArgument(2)).get(0).get("content").toString());
            assertFalse(context.path("retrieved_experiences").isEmpty());
            ObjectNode plan=mapper.createObjectNode();plan.putArray("focus").add("岗位能力");var qs=plan.putArray("questions");
            for(JsonNode slot:context.path("slots")) {
                var q=qs.addObject().put("kind",slot.path("kind").asText()).put("title","本岗位问题").put("prompt","说明一种具体技术的适用边界？题型："+slot.path("kind").asText()).put("reference","内部参考").put("bank_id","");
                q.putArray("rubric").add("解释边界");q.putArray("experience_ids").add(context.path("retrieved_experiences").get(0).path("id").asText());
            }
            return new AgentReply(plan.toString(),List.of());
        });
        var session=service.start(input);assertEquals("AI 改编（参考检索面经）",session.path("turns").get(0).path("source_name").asText());
        assertTrue(session.path("turns").get(0).path("experience_sources").get(0).has("attribution"));assertFalse(session.toString().contains("内部参考"));
        reset(gateway);
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(call->{
            JsonNode context=mapper.readTree(((List<Map<String,Object>>)call.getArgument(2)).get(0).get("content").toString());
            var plan=mapper.createObjectNode();plan.putArray("focus");var qs=plan.putArray("questions");
            for(JsonNode slot:context.path("slots")){var q=qs.addObject().put("kind",slot.path("kind").asText()).put("title","问题").put("prompt","如何解释边界？").put("reference","参考");q.putArray("rubric").add("依据");q.putArray("experience_ids").add("invented-source");}
            return new AgentReply(plan.toString(),List.of());
        });
        assertEquals("invalid_interview_output",assertThrows(ApiException.class,()->service.start(input)).getCode());
    }
    @Test void fullFlowScoresAllCategoriesAndPersistsCompleteReportWithExport() {
        var session=service.start(setupInput());reply(grade(false));String id=session.path("id").asText();
        for(int i=0;i<3;i++){session=service.answer(id,action(session));if(i<2)assertFalse(session.toString().contains("\"evaluation\""));}
        assertEquals("completed",session.path("status").asText());assertEquals(75,session.path("report").path("score").asInt());
        assertEquals(3,session.path("report").path("scored").asInt());
        for(JsonNode group:session.path("report").path("categories")){assertEquals(1,group.path("count").asInt());assertEquals(75,group.path("score").asInt());}
        assertEquals(session,service.detail(id));assertTrue(service.markdown(id).contains("本次训练复盘"));assertTrue(service.markdown(id).contains("fixture-gpt"));
        assertFalse(repository.list().toString().contains("课程订单系统"));verify(gateway,times(3)).agentTurn(any(),anyString(),anyList(),anyList());
    }
    @Test void followupsStayOnCurrentMainQuestionAndStopAtConfiguredDepth() {
        var input=setupInput().put("intensity","deep").put("feedback_mode","after_each");input.putArray("categories").add("project");
        var session=service.start(input);reply(grade(true));String id=session.path("id").asText();
        session=service.answer(id,action(session));assertEquals(1,session.path("turns").get(1).path("depth").asInt());
        assertTrue(session.path("turns").get(0).has("evaluation"));
        session=service.answer(id,action(session));assertEquals(2,session.path("turns").get(2).path("depth").asInt());
        session=service.answer(id,action(session));assertEquals(2,session.path("turns").get(3).path("main_index").asInt());assertEquals(0,session.path("turns").get(3).path("depth").asInt());
        assertEquals(5,repository.require(id).payload().path("questions").size());
    }
    @Test void skipAndEarlyFinishDoNotInventScoresOrCallModel() {
        var session=service.start(setupInput());String id=session.path("id").asText();session=service.skip(id,action(session));
        session=service.finish(id,action(session));assertTrue(session.path("report").path("score").isNull());
        assertEquals(1,session.path("report").path("skipped").asInt());assertEquals(2,session.path("report").path("unanswered").asInt());
        assertThrows(ApiException.class,()->service.answer(id,mapper.createObjectNode().put("revision",2).put("turn_id","x").put("answer","答案")));
        verifyNoInteractions(gateway,settings);
    }
    @Test void invalidScoreAndFabricatedQuoteCannotChangeAnswersOrRevision() {
        var session=service.start(setupInput());String id=session.path("id").asText();var invalid=grade(false);((ObjectNode)invalid.path("dimensions").path("correctness")).put("score",41);reply(invalid);
        assertEquals("invalid_interview_output",assertThrows(ApiException.class,()->service.answer(id,action(session))).getCode());
        invalid=grade(false);invalid.put("evidence","我从未说过的话");reply(invalid);assertThrows(ApiException.class,()->service.answer(id,action(session)));
        assertEquals(0,repository.require(id).revision());assertFalse(repository.require(id).payload().path("questions").get(0).has("answer"));
    }
    @Test void staleTabsAndWrongTurnCannotOverwriteSession() {
        var before=service.start(setupInput());String id=before.path("id").asText();service.skip(id,action(before));
        assertEquals(409,assertThrows(ApiException.class,()->service.answer(id,action(before))).getStatus());
        var current=service.detail(id);var wrong=action(current).put("turn_id","wrong");assertThrows(ApiException.class,()->service.answer(id,wrong));verifyNoInteractions(gateway);
    }
    @Test void changingSessionWhileModelRepliesDiscardsStaleResult() {
        var session=service.start(setupInput());String id=session.path("id").asText();
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(call->{service.finish(id,action(session));return new AgentReply(grade(false).toString(),List.of());});
        assertEquals(409,assertThrows(ApiException.class,()->service.answer(id,action(session))).getStatus());
        assertEquals("completed",service.detail(id).path("status").asText());assertEquals(0,service.detail(id).path("report").path("scored").asInt());
    }
    @Test void missingModelAndBadJsonKeepInterviewAvailableForRetry() {
        var session=service.start(setupInput());String id=session.path("id").asText();when(settings.load()).thenReturn(ModelConfiguration.empty());
        assertEquals("model_not_configured",assertThrows(ApiException.class,()->service.answer(id,action(session))).getCode());verifyNoInteractions(gateway);
        when(settings.load()).thenReturn(new ModelConfiguration("fixture-gpt","test",Map.of()));when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply("not JSON",List.of()));
        assertThrows(ApiException.class,()->service.answer(id,action(session)));assertEquals(0,service.detail(id).path("revision").asInt());
        reply(grade(false));assertEquals(1,service.answer(id,action(session)).path("revision").asInt());
    }
    @Test void jdPlanRespectsKindsAndRejectsEnglishOrUnknownSourceIds() {
        var input=setupInput().put("mode","ai");
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(call->{
            JsonNode context=mapper.readTree(((List<Map<String,Object>>)call.getArgument(2)).get(0).get("content").toString());
            ObjectNode plan=mapper.createObjectNode();plan.putArray("focus").add("岗位缓存能力");var questions=plan.putArray("questions");
            for(JsonNode slot:context.path("slots")){ObjectNode q=questions.addObject().put("kind",slot.path("kind").asText()).put("title","岗位能力问题").put("prompt","解释一个符合岗位要求的具体技术取舍，为什么这样选择？题型："+slot.path("kind").asText()).put("reference","说明需求与边界").put("bank_id","");q.putArray("rubric").add("技术选择理由").add("方案边界");}
            return new AgentReply(plan.toString(),List.of());
        });
        var session=service.start(input);assertEquals("fixture-gpt",session.path("planning_model").asText());assertEquals("AI 根据岗位材料出题",session.path("turns").get(0).path("source_name").asText());reset(gateway);
        ObjectNode invalid=mapper.createObjectNode();invalid.putArray("focus");var qs=invalid.putArray("questions");
        for(String kind:List.of("knowledge","algorithm","project")){var q=qs.addObject().put("kind",kind).put("title","测试").put("prompt","Explain locks").put("reference","参考");q.putArray("rubric").add("依据");}reply(invalid);
        assertThrows(ApiException.class,()->service.start(input));
        qs.forEach(q->{((ObjectNode)q).put("prompt","解释锁的适用边界？").put("bank_id","made-up-source");});reply(invalid);assertThrows(ApiException.class,()->service.start(input));
    }
    @Test void endpointsValidateInputAndNeverExportAnActiveSession() throws Exception {
        mvc.perform(post("/api/interviews").contentType("application/json").content("{\"role\":\"Java\",\"question_count\":100}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/interviews").contentType("application/json").content("{\"role\":\"Java\",\"categories\":[]}" )).andExpect(status().isBadRequest());
        mvc.perform(post("/api/interviews").contentType("application/json").content("{\"role\":\"Java\",\"mode\":\"ai\"}" )).andExpect(status().isBadRequest());
        var session=service.start(setupInput());String id=session.path("id").asText();mvc.perform(get("/api/interviews/"+id+"/report.md")).andExpect(status().isBadRequest());
        var completed=service.finish(id,action(session));mvc.perform(get("/api/interviews/"+id+"/report.md")).andExpect(status().isOk()).andExpect(header().string("Content-Type","text/markdown; charset=utf-8"));
        service.delete(id,action(completed));assertThrows(ApiException.class,()->service.detail(id));
    }
    @Test void algorithmRunFailureDoesNotClaimAcceptanceOrModifySession() {
        var input=setupInput();input.putArray("categories").add("algorithm");var session=service.start(input);String id=session.path("id").asText();
        assertEquals(503,assertThrows(ApiException.class,()->service.run(id,action(session).put("code","print(1)"))).getStatus());
        assertFalse(service.detail(id).path("turns").get(0).has("execution"));assertEquals(0,service.detail(id).path("revision").asInt());verifyNoInteractions(gateway);
    }

    @Test void changedCodeDiscardsOldExecutionBeforeGradingAndDisplaying() throws Exception {
        var input=setupInput();input.putArray("categories").add("algorithm");
        var session=service.start(input);String id=session.path("id").asText();
        var stored=repository.require(id);ObjectNode payload=stored.payload().deepCopy();
        ((ObjectNode)payload.path("questions").get(0)).putObject("execution").put("code_hash","old-code").putObject("result").put("status","accepted");
        repository.update(stored,payload);session=service.detail(id);
        reply(grade(false));var after=service.answer(id,action(session).put("code","print('new code')"));
        assertFalse(after.path("turns").get(0).has("execution"));
        var context=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gateway).agentTurn(any(),anyString(),context.capture(),anyList());
        var sent=(Map<?,?>)context.getValue().get(0);
        assertFalse(mapper.readTree(sent.get("content").toString()).has("execution"));
    }
}
