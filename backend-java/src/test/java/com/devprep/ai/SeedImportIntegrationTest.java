package com.devprep.ai;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import com.devprep.ai.domain.AttemptResult;
import com.devprep.ai.domain.PracticeQuestion;
import com.devprep.ai.domain.QuestionReference;
import com.devprep.ai.service.LearningService;
import com.devprep.ai.service.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:locallearning;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "devprep.config-file=target/test-settings/local-learning-settings.toml",
        "devprep.learning-file=classpath:seed/learning.json",
        "devprep.local-learning-file=",
        "devprep.seed-file=classpath:seed/java-foundations.md",
        "devprep.resources-file=classpath:seed/resources.json"
})
@Import(LearningIntegrationTest.ClockConfiguration.class)
@AutoConfigureMockMvc
class SeedImportIntegrationTest {
    @Autowired DevPrepProperties properties;
    @Autowired SeedService seeds;
    @Autowired LearningService learning;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired LearningIntegrationTest.TestClock testClock;
    @TempDir Path directory;

    @BeforeEach
    void resetFixtureData() {
        resetLocations();
        jdbc.update("DELETE FROM attempts");
        jdbc.update("DELETE FROM questions WHERE id LIKE 'local-test-%'");
        jdbc.update("DELETE FROM knowledge_points WHERE id LIKE 'local-test-%'");
        jdbc.update("UPDATE knowledge_points SET next_review_at=NULL");
        testClock.set(Instant.parse("2026-10-08T04:00:00Z"));
    }

    @AfterEach
    void resetLocations() {
        properties.setLearningFile("classpath:seed/learning.json");
        properties.setLocalLearningFile(null);
    }

    @Test
    void optionalPackExtendsBuiltinSeedAndPreservesFullSourceMetadataWithoutAnswers() throws Exception {
        int builtinQuestions = questionCount();
        int builtinTopics = topicCount();
        ObjectNode pack = localPack("local-test-provenance");
        topic(pack).withArray("prerequisites").add("java-equals");
        loadPack(pack);

        assertEquals(builtinQuestions + 1, questionCount());
        assertEquals(builtinQuestions + 1, learning.dashboard().question_count());
        assertEquals(builtinTopics + 1, topicCount());
        assertNotNull(learning.nextQuestion("java-equals"));
        PracticeQuestion next = learning.nextQuestion("local-test-provenance-topic");
        assertEquals("Fixture source", next.source_type());
        assertNull(next.source_material_id());
        assertEquals("Fixture source", next.source_name());
        assertEquals("https://example.com/snapshot/questions.md", next.source_url());
        assertEquals("questions.md", next.source_path());
        assertEquals("CC-BY-4.0", next.source_license());
        assertEquals("Fixture contributors", next.source_attribution());
        assertEquals("fixture-revision", next.source_revision());
        assertEquals("a".repeat(64), next.source_sha256());
        assertEquals("en", next.source_locale());
        assertEquals("reference", next.assessment_mode());
        assertEquals("imported", next.status());
        assertEquals("verbatim-html-markdown", next.content_format());
        JsonNode response = mapper.valueToTree(next);
        assertFalse(response.has("answer"));
        assertFalse(response.has("explanation"));
        assertFalse(response.has("rubric"));
        assertTrue(response.path("source_material_id").isNull());

        QuestionReference reference = learning.reference(next.id());
        assertEquals("<p>Original reference **answer**.</p>", ((JsonNode) reference.answer()).textValue());
        assertEquals(List.of(), reference.rubric());
        assertEquals("reference", reference.assessment_mode());
        assertEquals("对照原始答案，我的回答覆盖了主要内容", reference.self_assessment_prompt());
        assertEquals(next.source_url(), reference.source_url());
        assertEquals(next.source_license(), reference.source_license());
        assertEquals(next.content_format(), reference.content_format());
    }

    @Test
    void referenceModeRequiresIndependentBooleanAndStoresSelfAssessmentForHistory() throws Exception {
        loadPack(localPack("local-test-self"));
        String id = "local-test-self-q";
        ApiException invalid = assertThrows(ApiException.class,
                () -> learning.submitAttempt(id, "My answer", List.of(true)));
        assertEquals("invalid_self_assessment", invalid.getCode());
        assertEquals(0, learning.dashboard().total_attempts());
        for (Object invalidValue : List.of("true", 1)) {
            mvc.perform(post("/api/attempts").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("question_id", id, "answer", "My answer", "self_assessment", invalidValue))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_self_assessment"));
        }
        mvc.perform(post("/api/attempts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question_id", id, "answer", "My first answer", "self_assessment", false))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assessment_mode").value("reference"))
                .andExpect(jsonPath("$.self_assessment").value(false)).andExpect(jsonPath("$.correct").value(false))
                .andExpect(jsonPath("$.review_hours").value(4)).andExpect(jsonPath("$.rubric.length()").value(0));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT self_assessment FROM attempts", Boolean.class));
        assertEquals("[]", jdbc.queryForObject("SELECT rubric_hits_json FROM attempts", String.class));
        assertEquals("reference", jdbc.queryForObject("SELECT assessment_mode FROM attempts", String.class));
        AttemptResult mastered = learning.submitAttempt(id, "  My second answer  ", null, true);
        assertTrue(mastered.correct());
        assertEquals(Boolean.TRUE, mastered.self_assessment());
        assertEquals(24, mastered.review_hours());
        assertEquals("verbatim-html-markdown", mastered.content_format());
        assertEquals(Boolean.TRUE, learning.history("1").get(0).self_assessment());
        assertEquals("reference", learning.history("1").get(0).assessment_mode());
        assertEquals(Boolean.TRUE, learning.dashboard().recent_attempts().get(0).self_assessment());
        mvc.perform(get("/api/practice/next").param("knowledge_point_id", "local-test-self-topic"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessment_mode").value("reference"))
                .andExpect(jsonPath("$.answer").doesNotExist()).andExpect(jsonPath("$.rubric").doesNotExist());
    }

    @Test
    void repeatedInitializationKeepsImportedAnswersAttemptsAndSchedule() throws Exception {
        ObjectNode pack = localPack("local-test-repeat");
        loadPack(pack);
        learning.submitAttempt("local-test-repeat-q", "My answer", null, true);
        String review = jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "local-test-repeat-topic");
        int count = questionCount();
        question(pack).put("answer", "Changed source answer");
        topic(pack).put("title", "Changed title");
        writePack(pack, false);
        seeds.initialize();
        seeds.initialize();
        assertEquals(count, questionCount());
        assertEquals(1, learning.dashboard().total_attempts());
        assertEquals(review, jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "local-test-repeat-topic"));
        assertEquals("<p>Original reference **answer**.</p>", ((JsonNode) learning.reference("local-test-repeat-q").answer()).textValue());
        assertEquals("Fixture topic", learning.nextQuestion("local-test-repeat-topic").topic_title());
    }

    @Test
    void invalidLocalPackDoesNotInsertBuiltinOrLocalChanges() throws Exception {
        int questionsBefore = questionCount();
        int topicsBefore = topicCount();
        ObjectNode builtin = localPack("local-test-builtin");
        question(builtin).put("source_material_id", SeedService.BUILTIN_MATERIAL_ID);
        writePack(builtin, true);
        ObjectNode local = localPack("local-test-invalid");
        question(local).remove("source_url");
        writePack(local, false);
        assertThrows(IllegalStateException.class, seeds::initialize);
        assertEquals(questionsBefore, questionCount());
        assertEquals(topicsBefore, topicCount());
    }

    @Test
    void databaseFailureRollsBackAllTopicAndQuestionInserts() throws Exception {
        int questionsBefore = questionCount();
        int topicsBefore = topicCount();
        ObjectNode pack = localPack("local-test-db");
        ObjectNode rejected = question(pack).deepCopy();
        rejected.put("id", "local-test-db-rejected");
        pack.withArray("questions").add(rejected);
        writePack(pack, false);
        jdbc.execute("ALTER TABLE questions ADD CONSTRAINT local_test_reject_question CHECK (id <> 'local-test-db-rejected')");
        try {
            assertThrows(DataIntegrityViolationException.class, seeds::initialize);
            assertEquals(questionsBefore, questionCount());
            assertEquals(topicsBefore, topicCount());
        } finally {
            jdbc.execute("ALTER TABLE questions DROP CONSTRAINT local_test_reject_question");
        }
    }

    @Test
    void invalidSourceUrlHashAssessmentAndReferencesAreRejected() throws Exception {
        for (String field : List.of("source_url", "source_sha256", "assessment_mode", "rubric", "point", "source_material_id", "status")) {
            ObjectNode pack = localPack("local-test-validation");
            ObjectNode item = question(pack);
            switch (field) {
                case "source_url" -> item.put(field, "javascript:alert(1)");
                case "source_sha256" -> item.put(field, "invalid-hash");
                case "assessment_mode" -> item.put(field, "unknown");
                case "status" -> item.put(field, "unknown");
                case "rubric" -> item.withArray(field).add("Not an objective scoring point");
                case "point" -> item.put(field, "missing-point");
                case "source_material_id" -> item.put(field, "missing-material");
                default -> fail("Unknown fixture");
            }
            writePack(pack, false);
            assertThrows(IllegalStateException.class, seeds::initialize, "Expected rejection for " + field);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM questions WHERE id LIKE 'local-test-%'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_points WHERE id LIKE 'local-test-%'", Integer.class));
        }
    }

    @Test
    void unsetOptionalLocationSucceedsAndSpecifiedMissingFileFails() {
        int before = questionCount();
        properties.setLocalLearningFile("  ");
        seeds.initialize();
        assertEquals(before, questionCount());
        properties.setLocalLearningFile(directory.resolve("missing.json").toString());
        assertThrows(IllegalStateException.class, seeds::initialize);
        assertEquals(before, questionCount());
    }

    @Test
    void canonicalMetadataNamesAndExistingKnowledgePointsAreAccepted() throws Exception {
        ObjectNode pack = localPack("local-test-canonical");
        pack.withArray("topics").removeAll();
        ObjectNode item = question(pack);
        item.put("point", "java-equals");
        item.remove("source_license");
        item.put("license", "LicenseRef-Unspecified");
        item.put("attribution", item.remove("source_attribution").textValue());
        item.put("revision", item.remove("source_revision").textValue());
        item.put("source_locale", item.remove("locale").textValue());
        loadPack(pack);
        assertEquals("LicenseRef-Unspecified", learning.reference("local-test-canonical-q").source_license());
        assertEquals("en", learning.reference("local-test-canonical-q").source_locale());
    }

    private int questionCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM questions", Integer.class); }
    private int topicCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_points", Integer.class); }
    private ObjectNode topic(ObjectNode pack) { return (ObjectNode) pack.path("topics").get(0); }
    private ObjectNode question(ObjectNode pack) { return (ObjectNode) pack.path("questions").get(0); }

    private void loadPack(ObjectNode pack) throws Exception {
        writePack(pack, false);
        seeds.initialize();
    }

    private void writePack(ObjectNode pack, boolean builtin) throws Exception {
        Path path = directory.resolve(builtin ? "builtin.json" : "local.json");
        Files.writeString(path, mapper.writeValueAsString(pack));
        if (builtin) properties.setLearningFile(path.toString());
        else properties.setLocalLearningFile(path.toString());
    }

    private ObjectNode localPack(String prefix) {
        ObjectNode pack = mapper.createObjectNode();
        ObjectNode topic = pack.putArray("topics").addObject();
        topic.put("id", prefix + "-topic").put("domain", "Fixture domain").put("title", "Fixture topic")
                .put("summary", "Fixture summary");
        topic.putArray("prerequisites");
        ObjectNode question = pack.putArray("questions").addObject();
        question.put("id", prefix + "-q").put("point", prefix + "-topic").put("type", "short_answer")
                .put("stem", "<p>Original fixture question?</p>").put("answer", "<p>Original reference **answer**.</p>")
                .put("explanation", "Original source reference").put("assessment_mode", "reference")
                .put("content_format", "verbatim-html-markdown").put("source_name", "Fixture source")
                .put("source_url", "https://example.com/snapshot/questions.md").put("source_path", "questions.md")
                .put("source_license", "CC-BY-4.0").put("source_attribution", "Fixture contributors")
                .put("source_revision", "fixture-revision").put("source_sha256", "a".repeat(64)).put("locale", "en");
        question.putArray("options");
        question.putArray("rubric");
        question.putNull("source_material_id");
        return pack;
    }
}
