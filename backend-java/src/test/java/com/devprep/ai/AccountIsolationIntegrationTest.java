package com.devprep.ai;

import com.devprep.ai.auth.*;
import com.devprep.ai.data.*;
import com.devprep.ai.model.ModelSettingsService;
import com.devprep.ai.service.LearningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"devprep.auth-enabled=true","devprep.auth-secure-cookie=false",
        "spring.datasource.url=jdbc:h2:mem:accounts;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "devprep.local-learning-file=","devprep.config-file=target/account-test/settings.toml"})
@AutoConfigureMockMvc
class AccountIsolationIntegrationTest {
    static final Path DIRECTORY;
    static {
        try {
            DIRECTORY=Files.createTempDirectory("devprep-account-test-");
            Files.writeString(DIRECTORY.resolve("bootstrap.json"),"{\"accounts\":[{\"username\":\"owner\",\"password\":\"owner-test-password\"}]}");
        } catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("devprep.auth-bootstrap-file",()->DIRECTORY.resolve("bootstrap.json").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserAccounts accounts;
    @Autowired StudyRepository study;
    @Autowired ResumeRepository resumes;
    @Autowired JobRepository jobs;
    @Autowired InterviewRepository interviews;
    @Autowired LearningService learning;
    @Autowired JdbcTemplate jdbc;
    @Autowired ModelSettingsService settings;

    Cookie login(String name,String password) throws Exception {
        MvcResult result=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username",name,"password",password)))).andExpect(status().isOk()).andReturn();
        String header=result.getResponse().getHeader("Set-Cookie");
        assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Strict"));
        assertFalse(result.getResponse().getContentAsString().contains(password));
        return new Cookie(UserAccounts.COOKIE,header.split(";",2)[0].split("=",2)[1]);
    }
    WorkspaceContext.User friend() {
        return accounts.create("friend_"+UUID.randomUUID().toString().replace("-","").substring(0,12),"initial-test-pass");
    }
    Cookie changed(WorkspaceContext.User user) throws Exception {
        Cookie initial=login(user.username(),"initial-test-pass");
        var result=mvc.perform(post("/api/auth/password").cookie(initial).contentType(MediaType.APPLICATION_JSON)
                .content("{\"current_password\":\"initial-test-pass\",\"new_password\":\"private-test-pass\"}")).andExpect(status().isOk()).andReturn();
        return new Cookie(UserAccounts.COOKIE,result.getResponse().getHeader("Set-Cookie").split(";",2)[0].split("=",2)[1]);
    }
    @Test void unauthenticatedApisAndForgedIdentityHeadersCannotReadPrivateData() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/resumes").header("X-User","owner").header("X-Workspace","public"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").header("Origin","https://foreign.example").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"owner-test-password\"}")).andExpect(status().isForbidden());
    }
    @Test void firstLoginRequiresNewPasswordAndRevokesInitialSession() throws Exception {
        var user=friend();Cookie initial=login(user.username(),"initial-test-pass");
        mvc.perform(get("/api/dashboard").cookie(initial)).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("password_change_required"));
        Cookie next=changed(user);
        mvc.perform(get("/api/dashboard").cookie(next)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").cookie(initial)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").cookie(next)).andExpect(jsonPath("$.user.must_change_password").value(false));
    }
    @Test void existingOwnerDataStaysPrivateAcrossAllWorkspacesAndDownloads() throws Exception {
        var owner=accounts.users().get(0);var guest=friend();
        String chat,resume,job,interview;
        try(var scope=WorkspaceContext.use(owner)) {
            chat=study.create("mentor").id();study.append(chat,"user","owner-private-note",Map.of());
            resume=resumes.create("owner-private-resume","JD","resume-text","facts","").id();
            job=jobs.saveJob(null,mapper.createObjectNode().put("title","owner-private-job").put("url",""),-1).path("id").asText();
            interview=interviews.create(mapper.createObjectNode().put("title","owner-private-interview").put("role","Java").put("status","active")).id();
        }
        Cookie visitor=changed(guest);
        for(String path:new String[]{"/api/study/sessions/"+chat,"/api/resumes/"+resume,"/api/jobs/"+job,"/api/interviews/"+interview,"/api/interviews/"+interview+"/report.md"})
            mvc.perform(get(path).cookie(visitor)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/study/sessions/"+chat).cookie(visitor)).andExpect(status().isNotFound());
        mvc.perform(get("/api/study/sessions").cookie(visitor)).andExpect(jsonPath("$.sessions.length()").value(0));
        mvc.perform(get("/api/study/sessions/"+chat).cookie(login("owner","owner-test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].content").value("owner-private-note"));
    }
    @Test void pooledConnectionsAndTransactionsAlwaysUseTheirOwnSchema() {
        var owner=accounts.users().get(0);var a=friend();var b=friend();
        int before;
        try(var scope=WorkspaceContext.use(owner)) { before=learning.dashboard().total_attempts(); }
        try(var scope=WorkspaceContext.use(a)) {
            learning.submitAttempt("q-equals-01","hashCode 必须相同",null);
            assertEquals(1,learning.dashboard().total_attempts());
            jdbc.update("UPDATE knowledge_points SET next_review_at=? WHERE id=?","2099-01-01T00:00:00Z","java-equals-hashcode");
        }
        for(int i=0;i<12;i++) {
            try(var scope=WorkspaceContext.use(b)) { assertEquals(0,learning.dashboard().total_attempts()); assertFalse(study.sessions().stream().anyMatch(s->s.title().equals("owner-private-note"))); }
            try(var scope=WorkspaceContext.use(a)) { assertEquals(1,learning.dashboard().total_attempts()); }
            try(var scope=WorkspaceContext.use(owner)) { assertEquals(before,learning.dashboard().total_attempts()); }
        }
        assertNull(WorkspaceContext.current());
    }
    @Test void backgroundWorkCapturesAndClearsAccountContext() throws Exception {
        var guest=friend();var executor=Executors.newSingleThreadExecutor();
        try {
            Runnable task;
            try(var scope=WorkspaceContext.use(guest)) { task=WorkspaceContext.capture(()->study.create("quiz")); }
            executor.submit(task).get();
            assertNull(executor.submit(WorkspaceContext::current).get());
            try(var scope=WorkspaceContext.use(guest)) { assertEquals(1,study.sessions().size()); }
        } finally {executor.shutdownNow();}
    }
    @Test void membersCannotManageAccountsOrReadOrChangeGatewayConfiguration() throws Exception {
        Cookie visitor=changed(friend());
        mvc.perform(get("/api/auth/accounts").cookie(visitor)).andExpect(status().isForbidden());
        mvc.perform(get("/api/models").cookie(visitor)).andExpect(status().isForbidden());
        mvc.perform(post("/api/settings").cookie(visitor).contentType(MediaType.APPLICATION_JSON).content("{\"toml\":\"\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/settings/import-codex").cookie(visitor).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        var snapshot=mvc.perform(get("/api/settings").cookie(visitor)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(snapshot.contains("toml"));assertFalse(snapshot.contains("base_url"));
        Cookie owner=login("owner","owner-test-password");
        mvc.perform(get("/api/auth/accounts").cookie(owner)).andExpect(status().isOk());
    }
    @Test void passwordResetRevokesAllSessionsAndDoesNotDiscardLearningData() throws Exception {
        var user=friend();Cookie first=changed(user),second=login(user.username(),"private-test-pass");
        try(var scope=WorkspaceContext.use(user)) { study.create("mentor"); }
        accounts.resetPassword(user.username(),"replacement-pass");
        for(Cookie old:new Cookie[]{first,second}) mvc.perform(get("/api/dashboard").cookie(old)).andExpect(status().isUnauthorized());
        Cookie replacement=login(user.username(),"replacement-pass");
        mvc.perform(get("/api/auth/me").cookie(replacement)).andExpect(jsonPath("$.user.must_change_password").value(true));
        try(var scope=WorkspaceContext.use(user)) {assertEquals(1,study.sessions().size());}
    }
    @Test void rateLimitsRepeatedInvalidLoginAndLogoutInvalidatesCookie() throws Exception {
        String name="unknown_"+UUID.randomUUID().toString().substring(0,8);
        for(int i=0;i<8;i++) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(Map.of("username",name,"password","invalid-pass")))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(Map.of("username",name,"password","invalid-pass"))))
                .andExpect(status().isTooManyRequests());
        Cookie owner=login("owner","owner-test-password");
        mvc.perform(post("/api/auth/logout").cookie(owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(get("/api/dashboard").cookie(owner)).andExpect(status().isUnauthorized());
    }
    @Test void memberModelOverrideDoesNotChangeOwnersConfig() throws Exception {
        var owner=accounts.users().get(0);var visitor=friend();
        try(var scope=WorkspaceContext.use(owner)) {
            settings.save("model = \"owner-model\"\nmodel_provider = \"gateway\"\n[model_providers.gateway]\nname = \"fixture\"\nbase_url = \"http://127.0.0.1:9/v1\"\nwire_api = \"responses\"\n");
            assertEquals("owner-model",settings.load().model());
        }
        try(var scope=WorkspaceContext.use(visitor)) { assertEquals("gpt-6.1-sol",settings.load().model()); }
        try(var scope=WorkspaceContext.use(owner)) { assertEquals("owner-model",settings.load().model()); }
    }
}
