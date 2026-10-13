package com.devprep.ai;

import com.devprep.ai.data.JsonCodec;
import com.devprep.ai.data.MaterialRepository;
import com.devprep.ai.api.ApiException;
import com.devprep.ai.domain.AttemptResult;
import com.devprep.ai.domain.Dashboard;
import com.devprep.ai.domain.ImportedMaterial;
import com.devprep.ai.domain.MaterialDetail;
import com.devprep.ai.domain.PracticeQuestion;
import com.devprep.ai.service.LearningService;
import com.devprep.ai.service.MaterialService;
import com.devprep.ai.service.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:learning;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=true",
        "devprep.config-file=target/test-settings/settings.toml",
        "devprep.learning-file=classpath:seed/learning.json",
        "devprep.local-learning-file=",
        "devprep.seed-file=classpath:seed/java-foundations.md",
        "devprep.resources-file=classpath:seed/resources.json"
})
@Import(LearningIntegrationTest.ClockConfiguration.class)
@AutoConfigureMockMvc
class LearningIntegrationTest {
    private static final Instant FIXED = Instant.parse("2026-10-08T04:00:00Z");

    @Autowired LearningService learning;
    @Autowired MaterialService materials;
    @Autowired SeedService seeds;
    @Autowired MaterialRepository materialRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired ObjectMapper mapper;
    @Autowired TestClock testClock;
    @Autowired MockMvc mvc;

    @BeforeEach
    void clearUserDataAndSchedules() {
        jdbc.update("DELETE FROM attempts");
        jdbc.update("UPDATE questions SET source_material_id=? WHERE source_material_id IN (SELECT id FROM materials WHERE source_type=?)",
                SeedService.BUILTIN_MATERIAL_ID, "用户导入");
        jdbc.update("DELETE FROM materials WHERE source_type=?", "用户导入");
        jdbc.update("UPDATE knowledge_points SET next_review_at=NULL");
        testClock.set(FIXED);
    }

    @Test
    void utcScheduleAndLocalDayBoundaryAreConsistent() {
        AttemptResult result = learning.submitAttempt("q-equals-01", "hashCode 必须相同", null);
        assertEquals("2026-10-09T04:00:00Z", result.review_at());
        assertEquals(1, result.streak());

        jdbc.update("UPDATE attempts SET created_at=?", "2026-10-07T16:30:00Z");
        Dashboard dashboard = learning.dashboard();
        assertEquals("2026-10-08", dashboard.date());
        assertEquals(1, dashboard.studied_today());
        assertTrue(dashboard.due_topics().stream().noneMatch(topic -> topic.id().equals("java-equals")));
    }

    @Test
    void futureUtcTimestampIsNotDueInNonUtcZone() {
        jdbc.update("UPDATE knowledge_points SET next_review_at=?", "2026-10-08T06:00:00Z");
        assertEquals(0, learning.dashboard().due_count());
        jdbc.update("UPDATE knowledge_points SET next_review_at=? WHERE id=?", "2026-10-08T04:00:00Z", "java-equals");
        assertEquals("java-equals", learning.nextQuestion(null).knowledge_point_id());
        assertEquals(1, learning.dashboard().due_count());
    }

    @Test
    void dailyCountUsesBothLocalMidnightBoundaries() {
        learning.submitAttempt("q-equals-01", "hashCode 必须相同", null);
        for (String stamp : List.of("2026-10-07T15:59:59Z", "2026-10-08T16:00:00Z")) {
            jdbc.update("UPDATE attempts SET created_at=?", stamp);
            assertEquals(0, learning.dashboard().studied_today());
        }
        for (String stamp : List.of("2026-10-07T16:00:00Z", "2026-10-08T15:59:59Z")) {
            jdbc.update("UPDATE attempts SET created_at=?", stamp);
            assertEquals(1, learning.dashboard().studied_today());
        }
    }

    @Test
    void attemptStreakUsesSequenceForSameSecondAndResetsOnWrongAnswer() {
        String correct = "hashCode 必须相同";
        String wrong = "hashCode 必须不同";
        List<AttemptResult> results = List.of(
                learning.submitAttempt("q-equals-01", correct, null),
                learning.submitAttempt("q-equals-01", correct, null),
                learning.submitAttempt("q-equals-01", correct, null),
                learning.submitAttempt("q-equals-01", correct, null),
                learning.submitAttempt("q-equals-01", wrong, null),
                learning.submitAttempt("q-equals-01", correct, null));
        assertEquals(List.of(1, 2, 3, 4, 0, 1), results.stream().map(AttemptResult::streak).toList());
        assertEquals(List.of(24, 72, 72, 168, 4, 24), results.stream().map(AttemptResult::review_hours).toList());
        assertEquals(6, learning.dashboard().total_attempts());
        assertEquals("2026-10-09T04:00:00Z", results.get(5).review_at());
        assertEquals(List.of(true, false), learning.history("2").stream().map(item -> item.is_correct()).toList());
    }

    @Test
    void invalidAnswerOrRubricDoesNotCreateAttempt() {
        assertApiError(400, "invalid_answer", () -> learning.submitAttempt("q-equals-01", "other", null));
        assertApiError(400, "invalid_answer", () -> learning.submitAttempt("q-equals-02", "a".repeat(8001), List.of(true, true, true)));
        assertApiError(400, "invalid_answer", () -> learning.submitAttempt("q-equals-02", "bad" + (char) 0xd800, List.of(true, true, true)));
        assertApiError(400, "invalid_rubric", () -> learning.submitAttempt("q-equals-02", "answer", List.of(true)));
        assertEquals(0, learning.dashboard().total_attempts());
        assertNull(jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "java-equals"));
    }

    @Test
    void answerAtCodePointLimitIsAccepted() {
        String answer = "a".repeat(8000);
        learning.submitAttempt("q-equals-02", answer, List.of(true, true, true));
        assertEquals(answer, json.readTree(jdbc.queryForObject("SELECT answer_json FROM attempts", String.class)).textValue());
    }

    @Test
    void scheduleFailureRollsBackAttemptInsert() {
        jdbc.execute("ALTER TABLE knowledge_points ADD CONSTRAINT test_schedule_failure CHECK (id <> 'java-equals' OR next_review_at IS NULL)");
        try {
            assertThrows(DataIntegrityViolationException.class,
                    () -> learning.submitAttempt("q-equals-01", "hashCode 必须相同", null));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Integer.class));
            assertNull(jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "java-equals"));
        } finally { jdbc.execute("ALTER TABLE knowledge_points DROP CONSTRAINT test_schedule_failure"); }
    }

    @Test
    void shortAnswerStoresPartialRubricScoreAndRoundsPublicScore() {
        AttemptResult result = learning.submitAttempt("q-equals-02", "An answer", List.of(true, false, true));
        assertEquals(0.67, result.score());
        assertFalse(result.correct());
        assertEquals(4, result.review_hours());
        assertEquals(2.0 / 3.0, jdbc.queryForObject("SELECT score FROM attempts", Double.class), 0.00001);
        assertEquals(List.of(true, false, true), json.booleans(jdbc.queryForObject("SELECT rubric_hits_json FROM attempts", String.class)));
    }

    @Test
    void practiceQuestionDoesNotLeakReferenceAndDueQuestionIsSelectedFirst() {
        jdbc.update("UPDATE knowledge_points SET next_review_at=? WHERE id=?", "2026-10-08T03:59:00Z", "java-hashmap");
        PracticeQuestion next = learning.nextQuestion(null);
        assertEquals("java-hashmap", next.knowledge_point_id());
        JsonNode shape = mapper.valueToTree(next);
        assertFalse(shape.has("answer"));
        assertFalse(shape.has("rubric"));
    }

    @Test
    void selectionPrioritizesWrongAndMissingScheduleThenUsesLeastAttemptedQuestion() {
        learning.submitAttempt("q-equals-01", "hashCode 必须不同", null);
        assertEquals("java-equals", learning.nextQuestion(null).knowledge_point_id());
        assertNotEquals("q-equals-01", learning.nextQuestion("java-equals").id());
        jdbc.update("UPDATE knowledge_points SET next_review_at=NULL WHERE id=?", "java-equals");
        assertEquals("java-equals", learning.nextQuestion(null).knowledge_point_id());
    }

    @Test
    void nextQuestionAggregatesSelectionForThousandsOfQuestions() {
        int questionCountBefore = learning.dashboard().question_count();
        String pointId = "scale-test-point";
        jdbc.update("INSERT INTO knowledge_points(id,domain,title,summary,prerequisites_json,created_at) VALUES(?,?,?,?,?,?)",
                pointId, "test", "Scale test", "Selection query test", "[]", "2026-10-08T04:00:00Z");
        List<Object[]> questions = new ArrayList<>(2500);
        for (int i = 0; i < 2500; i++) {
            questions.add(new Object[]{"scale-q-%04d".formatted(i), pointId, "single_choice", "Scale question " + i,
                    "[\"A\",\"B\"]", "\"A\"", "Explanation", "[]", null});
        }
        try {
            jdbc.batchUpdate("""
                    INSERT INTO questions(id,knowledge_point_id,question_type,stem,options_json,answer_json,
                                          explanation,rubric_json,source_material_id,content_locale,status)
                    VALUES(?,?,?,?,?,?,?,?,?,'zh-CN','reviewed')
                    """, questions);
            assertEquals("scale-q-0000", learning.nextQuestion(pointId).id());
            assertEquals(questionCountBefore + 2500, learning.dashboard().question_count());
        } finally {
            jdbc.update("DELETE FROM questions WHERE knowledge_point_id=?", pointId);
            jdbc.update("DELETE FROM knowledge_points WHERE id=?", pointId);
        }
        assertEquals(questionCountBefore, learning.dashboard().question_count());
    }

    @Test
    void nextQuestionRejectsUnknownKnowledgePoint() {
        assertApiError(404, "question_not_found", () -> learning.nextQuestion("missing-point"));
    }

    @Test
    void correctStreakIsSharedByQuestionsWithinATopicAndIndependentOfOtherTopics() {
        learning.submitAttempt("q-equals-01", "hashCode 必须相同", null);
        assertEquals(2, learning.submitAttempt("q-equals-02", "Answer", List.of(true, true, true)).streak());
        assertEquals(1, learning.submitAttempt("q-string-01", "使用 StringBuilder", null).streak());
    }

    @Test
    void materialImportSearchDetailDeleteAndChunkCascade() {
        ImportedMaterial created = materials.importMaterial("../../course-notes.md",
                "# Cache\nMarkerLruCache stores values.\n## Eviction\nMarkerLruCache removes old entries.");
        assertEquals("course-notes.md", created.filename());
        assertEquals(2, created.chunk_count());
        MaterialDetail details = materials.detail(created.id());
        assertEquals(2, details.chunks().size());
        assertEquals("Eviction", details.chunks().get(1).heading());
        assertFalse(materials.search("MarkerLruCache", 6).isEmpty());
        materials.deleteMaterial(created.id());
        assertApiError(404, "material_not_found", () -> materials.detail(created.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM material_chunks WHERE material_id=?", Integer.class, created.id()));
    }

    @Test
    void fencedHeadingsStayInsideCodeAndInvalidMaterialDoesNotPersist() {
        ImportedMaterial created = materials.importMaterial("sample.md", "# Example\n```java\n# not-a-heading\n```\nAfter code.");
        assertEquals(1, created.chunk_count());
        assertTrue(materials.detail(created.id()).chunks().get(0).content().contains("# not-a-heading"));
        assertApiError(400, "empty_material", () -> materials.importMaterial("empty.md", "# Heading only\n## Also heading"));
        assertApiError(400, "unsupported_file", () -> materials.importMaterial("notes.pdf", "Text"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM materials WHERE source_type=?", Integer.class, "用户导入"));
    }

    @Test
    void materialSizeIsMeasuredInUtf8BytesAndMalformedUnicodeIsRejected() {
        assertApiError(413, "material_too_large", () -> materials.importMaterial("notes.txt", "汉".repeat(333_334)));
        assertApiError(400, "invalid_material", () -> materials.importMaterial("notes.txt", "invalid" + (char) 0xd800));
        assertApiError(400, "empty_material", () -> materials.importMaterial("notes.txt", "  "));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM materials WHERE source_type=?", Integer.class, "用户导入"));
    }

    @Test
    void searchIgnoresQuestionWordsAndSupportsChineseAndLimits() {
        assertEquals(List.of(), materials.search("What is QuasarUniqueMarker?", 6));
        ImportedMaterial created = materials.importMaterial("markers.md", "# Novel\n青龙甲盾 青龙甲盾 青龙甲盾 青龙甲盾\n## Two\n青龙甲盾\n## Three\n青龙甲盾");
        assertEquals(1, materials.search("青龙甲盾", 1).size());
        assertEquals(created.id(), materials.search("青龙甲盾", 6).get(0).material_id());
        assertEquals(3, materials.search("青龙甲盾", 6).size());
    }

    @Test
    void builtinCannotDeleteAndReferencedMaterialIsProtected() {
        assertApiError(403, "protected_material", () -> materials.deleteMaterial(SeedService.BUILTIN_MATERIAL_ID));
        ImportedMaterial created = materials.importMaterial("referenced.md", "A source note.");
        jdbc.update("UPDATE questions SET source_material_id=? WHERE id=?", created.id(), "q-equals-01");
        assertApiError(409, "material_in_use", () -> materials.deleteMaterial(created.id()));
        assertTrue(materialRepository.exists(created.id()));
    }

    @Test
    void seedCanRunAgainWithoutReplacingProgressOrSchedule() {
        learning.submitAttempt("q-equals-01", "hashCode 必须相同", null);
        String expectedSchedule = jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "java-equals");
        seeds.initialize();
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Integer.class));
        assertEquals(expectedSchedule, jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "java-equals"));
    }

    @Test
    void learningApiPreservesSnakeCaseResponseAndStrictPayloadTypes() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/practice/next").param("knowledge_point_id", "java-equals"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.knowledge_point_id").value("java-equals"))
                .andExpect(jsonPath("$.source_type").value("内置资料"))
                .andExpect(jsonPath("$.source_material_id").value(SeedService.BUILTIN_MATERIAL_ID))
                .andExpect(jsonPath("$.assessment_mode").value("rubric"))
                .andExpect(jsonPath("$.content_format").value("markdown"))
                .andExpect(jsonPath("$.answer").doesNotExist()).andExpect(jsonPath("$.rubric").doesNotExist());
        mvc.perform(get("/api/practice/reference").param("question_id", "q-equals-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").isString())
                .andExpect(jsonPath("$.rubric.length()").value(3)).andExpect(jsonPath("$.topic_title").isString());
        mvc.perform(post("/api/attempts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question_id", "q-equals-01", "answer", 123))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_answer"));
        mvc.perform(post("/api/attempts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question_id", "q-equals-02", "answer", "Answer", "rubric_hits", List.of(1, 1, 1)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_rubric"));
        mvc.perform(post("/api/attempts").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("question_id", "q-equals-01", "answer", "hashCode 必须相同"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.review_at").value("2026-10-09T04:00:00Z"))
                .andExpect(jsonPath("$.review_hours").value(24));
        mvc.perform(get("/api/history").param("limit", "bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_limit"));
        mvc.perform(get("/api/history").param("limit", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attempts.length()").value(1));
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_attempts").value(1))
                .andExpect(jsonPath("$.studied_today").value(1));
    }

    @Test
    void materialApiRoundTripAndErrors() throws Exception {
        String response = mvc.perform(post("/api/materials/import").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("filename", "../api-notes.md", "content", "# API notes\nText."))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.material.filename").value("api-notes.md"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(response).path("material").path("id").asText();
        mvc.perform(get("/api/materials/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.material.content").value("# API notes\nText."))
                .andExpect(jsonPath("$.chunks.length()").value(1));
        mvc.perform(delete("/api/materials/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(get("/api/materials/" + id)).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("material_not_found"));
        mvc.perform(delete("/api/materials/" + SeedService.BUILTIN_MATERIAL_ID)).andExpect(status().isForbidden());
        mvc.perform(post("/api/materials/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filename\":true,\"content\":\"Text\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("invalid_material"));
    }

    @Test
    void concurrentSubmissionsEachObserveThePrecedingAttempt() throws Exception {
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workers);
        List<Future<AttemptResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return learning.submitAttempt("q-equals-01", "hashCode 必须相同", null);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AttemptResult> results = new ArrayList<>();
            for (Future<AttemptResult> future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3, 4), results.stream().map(AttemptResult::streak).sorted().toList());
        } finally { executor.shutdownNow(); }
        assertEquals(4, learning.dashboard().total_attempts());
        assertEquals("2026-10-15T04:00:00Z", jdbc.queryForObject("SELECT next_review_at FROM knowledge_points WHERE id=?", String.class, "java-equals"));
    }

    private void assertApiError(int status, String code, org.junit.jupiter.api.function.Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(status, error.getStatus());
        assertEquals(code, error.getCode());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary
        TestClock learningTestClock() { return new TestClock(FIXED, ZoneId.of("Asia/Shanghai")); }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final ZoneId zone;
        TestClock(Instant instant, ZoneId zone) { this.instant = new AtomicReference<>(instant); this.zone = zone; }
        void set(Instant value) { instant.set(value); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }
}
