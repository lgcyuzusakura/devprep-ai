package com.devprep.ai;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.StudyRepository;
import com.devprep.ai.model.*;
import com.devprep.ai.service.AlgorithmService;
import com.devprep.ai.service.StudyAgentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:study-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "devprep.local-learning-file=", "devprep.algorithm-file=", "devprep.agent-min-interval-millis=0",
        "devprep.config-file=target/test-study/settings.toml"})
class StudyWorkflowIntegrationTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static HttpServer runner;
    static {
        try {
            runner=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            runner.createContext("/run", exchange -> {
                var body=MAPPER.readTree(exchange.getRequestBody());
                List<Object> cases=new ArrayList<>();
                for (var input:body.path("inputs")) cases.add(Map.of("status","ok","stdout","[0,1]","stderr","synthetic-output","duration_ms",1));
                byte[] encoded=MAPPER.writeValueAsBytes(Map.of("status","completed","cases",cases));
                exchange.sendResponseHeaders(200,encoded.length); exchange.getResponseBody().write(encoded); exchange.close();
            }); runner.start();
        } catch(Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("devprep.runner-url", () -> "http://127.0.0.1:"+runner.getAddress().getPort()); }
    @AfterAll static void stop() { runner.stop(0); }
    @Autowired StudyRepository repository;
    @Autowired StudyAgentService agent;
    @Autowired AlgorithmService algorithms;
    @MockitoBean ModelSettingsService settings;
    @MockitoBean ModelGateway gateway;
    @BeforeEach void configure() {
        when(settings.load()).thenReturn(new ModelConfiguration("test-gpt", "test", Map.of()));
        when(gateway.agentStream(any(),anyString(),anyList(),anyList(),any())).thenCallRealMethod();
    }

    @Test void conversationUsesToolsAndKeepsFollowupContext() {
        var session=repository.create("quiz");
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList()))
                .thenReturn(new AgentReply("",List.of(new AgentReply.ToolCall("call-1","pick_question","{}"))))
                .thenReturn(new AgentReply("请先回答这道题。",List.of()));
        var reply=agent.send(session.id(),"给我出一道题",(name,data)->{});
        assertTrue(reply.payload().has("question"));
        assertFalse(reply.payload().path("question").has("answer"));
        assertEquals(2,repository.messages(session.id()).size());
        reset(gateway);
        when(gateway.agentStream(any(),anyString(),anyList(),anyList(),any())).thenCallRealMethod();
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(invocation -> {
            List<Map<String,Object>> transcript=invocation.getArgument(2);
            assertTrue(transcript.stream().anyMatch(m->m.get("content").toString().contains("上轮练习题")));
            assertTrue(transcript.stream().anyMatch(m->m.get("content").equals("我的答案是使用锁")));
            return new AgentReply("继续分析锁的作用。",List.of());
        });
        agent.send(session.id(),"我的答案是使用锁",(name,data)->{});
        assertEquals(4,repository.messages(session.id()).size());
        repository.delete(session.id());
        assertThrows(ApiException.class,()->repository.messages(session.id()));
    }
    @Test void unknownToolsCannotInvokeOtherCapabilities() {
        var session=repository.create("mentor");
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenReturn(new AgentReply("",List.of(new AgentReply.ToolCall("x","run_shell","{}"))), new AgentReply("请讨论计算机知识。",List.of()));
        var answer=agent.send(session.id(),"讲讲线程",(n,d)->{});
        assertEquals("没有这个学习工具",answer.payload().path("tools").path(0).path("result").path("error").asText());
    }
    @Test void concurrentMessagesAreRejectedAndLockIsReleased() throws Exception {
        var a=repository.create("mentor"); var b=repository.create("mentor");
        CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenAnswer(invocation->{started.countDown();release.await(3,TimeUnit.SECONDS);return new AgentReply("回答",List.of());});
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<?> first=pool.submit(()->agent.send(a.id(),"第一个问题",(n,d)->{}));
            assertTrue(started.await(3,TimeUnit.SECONDS));
            assertEquals("agent_busy",assertThrows(ApiException.class,()->agent.send(b.id(),"第二个问题",(n,d)->{})).getCode());
            assertTrue(repository.messages(b.id()).isEmpty());
            release.countDown();first.get(3,TimeUnit.SECONDS);
            agent.send(b.id(),"再问一次",(n,d)->{});
        } finally { release.countDown();pool.shutdownNow(); }
    }
    @Test void failureIsPersistedWithoutDeletingUserMessage() {
        var session=repository.create("mentor");
        when(gateway.agentTurn(any(),anyString(),anyList(),anyList())).thenThrow(new ApiException(429,"provider_rate_limit","请稍后再试。"));
        assertThrows(ApiException.class,()->agent.send(session.id(),"讲讲TCP",(n,d)->{}));
        var messages=repository.messages(session.id()); assertEquals(2,messages.size());assertTrue(messages.get(1).payload().path("failed").asBoolean());
    }
    @Test void unconfiguredModelUsesLocalSearchWithoutAnyModelRequest() {
        when(settings.load()).thenReturn(ModelConfiguration.empty());
        var reply=agent.send(repository.create("mentor").id(),"HashMap",(n,d)->{});
        assertTrue(reply.payload().path("local_only").asBoolean()); verifyNoInteractions(gateway);
    }
    @Test void algorithmCasesAreJudgedAndHiddenInputsStayPrivate() {
        var detail=algorithms.detail("two-sum");assertFalse(detail.has("tests"));
        var result=algorithms.judge("two-sum","python","print('[0,1]')",true);
        assertEquals("wrong_answer",result.get("status"));
        var cases=(List<Map<String,Object>>)result.get("cases");
        assertEquals(6,cases.size());assertTrue(cases.get(0).containsKey("expected"));
        assertFalse(cases.get(2).containsKey("input"));assertFalse(cases.get(2).containsKey("actual"));assertFalse(cases.get(2).containsKey("stderr"));
        assertFalse(algorithms.history("two-sum").isEmpty());
    }
    @Test void invalidSubmissionsNeverReachRunner() {
        assertThrows(ApiException.class,()->algorithms.judge("two-sum","shell","echo hi",false));
        assertThrows(ApiException.class,()->algorithms.judge("two-sum","python","",false));
        assertThrows(ApiException.class,()->algorithms.detail("missing"));
    }
    @Test void algorithmCollectionsMatchDeduplicatedCatalogAndKeepExistingIds() {
        var expected=Map.of("hot100",100L,"first100",100L,"leetcode75",75L,"interview150",150L,"programming",33L,"dp",46L,"graph",36L,"binary",32L);
        var collections=algorithms.collections();
        assertEquals(expected.size(),collections.size());
        for (var collection:collections) assertEquals(expected.get(collection.get("id")), collection.get("count"));
        var problems=algorithms.list();
        assertEquals(360,problems.size());
        assertEquals(problems.size(),problems.stream().map(p->p.get("id")).distinct().count());
        assertTrue(algorithms.detail("lc-001-two-sum").path("collections").toString().contains("first100"));
        assertTrue(algorithms.detail("lc-1071-greatest-common-divisor-of-strings").path("collections").toString().contains("leetcode75"));
        assertFalse(algorithms.detail("two-sum").path("official").asBoolean());
    }
    @Test void stopPreservesPartialAnswerAndRetryDoesNotDuplicateUser() {
        var session=repository.create("mentor");
        doAnswer(invocation -> {
            java.util.function.Consumer<String> delta=invocation.getArgument(4);
            delta.accept("TCP 保证有序传输。");
            agent.stop(session.id());
            return new AgentReply("TCP 保证有序传输。",List.of());
        }).when(gateway).agentStream(any(),anyString(),anyList(),anyList(),any());
        var answer=agent.send(session.id(),"讲讲 TCP",(n,d)->{});
        assertTrue(answer.payload().path("cancelled").asBoolean());
        assertEquals("TCP 保证有序传输。",answer.content());
        assertFalse(agent.busy(session.id()));
        doAnswer(invocation -> {
            List<Map<String,Object>> transcript=invocation.getArgument(2);
            assertEquals(1,transcript.stream().filter(m->"user".equals(m.get("role"))).count());
            java.util.function.Consumer<String> delta=invocation.getArgument(4);delta.accept("重新讲解 TCP。");
            return new AgentReply("重新讲解 TCP。",List.of());
        }).when(gateway).agentStream(any(),anyString(),anyList(),anyList(),any());
        agent.run(agent.begin(session.id(),null,true),(n,d)->{});
        assertEquals(2,repository.messages(session.id()).size());
        assertEquals("重新讲解 TCP。",repository.messages(session.id()).get(1).content());
    }
    @Test void reservationRejectsSecondTurnBeforeAnyModelRequest() {
        var session=repository.create("mentor");
        var first=agent.begin(session.id(),"你好",false);
        assertEquals("agent_busy",assertThrows(ApiException.class,()->agent.begin(session.id(),"再次",false)).getCode());
        agent.stop(session.id()); agent.run(first,(n,d)->{});
        verifyNoInteractions(gateway);
        assertFalse(agent.busy(session.id()));
    }
    @Test void sessionRenameIsValidatedAndPersists() {
        var session=repository.create("mentor");
        repository.rename(session.id(),"TCP 复习");
        assertEquals("TCP 复习",repository.require(session.id()).title());
        assertThrows(ApiException.class,()->repository.rename(session.id()," "));
    }
}
