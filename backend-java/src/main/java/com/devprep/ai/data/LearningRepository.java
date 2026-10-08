package com.devprep.ai.data;

import com.devprep.ai.domain.Dashboard;
import com.devprep.ai.domain.HistoryAttempt;
import com.devprep.ai.domain.PracticeQuestion;
import com.devprep.ai.domain.QuestionData;
import com.devprep.ai.domain.QuestionReference;
import com.devprep.ai.domain.RecentAttempt;
import com.devprep.ai.domain.TopicSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class LearningRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final Clock clock;

    public LearningRepository(JdbcTemplate jdbc, JsonCodec json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    private TopicSummary topicSummary(ResultSet rs) throws SQLException {
        int attempts = rs.getInt("attempts");
        int correct = rs.getInt("correct");
        return new TopicSummary(rs.getString("id"), rs.getString("domain"),
                rs.getString("title"), rs.getString("summary"), attempts, correct,
                attempts == 0 ? null : Math.round((double) correct / attempts * 100.0) / 100.0,
                rs.getString("last_practiced"), rs.getString("next_review_at"),
                json.strings(rs.getString("prerequisites_json")));
    }

    private RecentAttempt recentAttempt(ResultSet rs) throws SQLException {
        return new RecentAttempt(
            rs.getString("id"), json.readTree(rs.getString("answer_json")), rs.getDouble("score"),
            rs.getInt("is_correct") != 0, rs.getString("created_at"), rs.getString("stem"),
            rs.getString("question_type"), rs.getString("topic"), rs.getString("domain"),
            rs.getString("assessment_mode"), (Boolean) rs.getObject("self_assessment"));
    }

    public Dashboard dashboard() {
        String now = now();
        List<TopicSummary> topics = topicSummaries();
        List<TopicSummary> due = new ArrayList<>();
        for (TopicSummary topic : topics) {
            if (topic.next_review_at() == null || topic.next_review_at().compareTo(now) <= 0) due.add(topic);
        }
        due.sort((left, right) -> {
            int byReview = compareNullFirst(left.next_review_at(), right.next_review_at());
            if (byReview != 0) return byReview;
            int byAttempts = Integer.compare(left.attempts(), right.attempts());
            return byAttempts != 0 ? byAttempts : left.title().compareTo(right.title());
        });
        LocalDate localDay = LocalDate.now(clock);
        String today = localDay.toString();
        String dayStart = localDay.atStartOfDay(clock.getZone()).toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String dayEnd = localDay.plusDays(1).atStartOfDay(clock.getZone()).toOffsetDateTime()
                .withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        int studied = jdbc.queryForObject("SELECT COUNT(*) FROM attempts WHERE created_at >= ? AND created_at < ?",
                Integer.class, dayStart, dayEnd);
        int total = jdbc.queryForObject("SELECT COUNT(*) FROM attempts", Integer.class);
        int questionCount = jdbc.queryForObject("SELECT COUNT(*) FROM questions", Integer.class);
        return new Dashboard(today, studied, total, due.size(), topics.size(), questionCount, due.stream().limit(6).toList(),
                topics, recentAttempts(5));
    }

    public List<TopicSummary> topicSummaries() {
        return jdbc.query("""
                SELECT kp.id,kp.domain,kp.title,kp.summary,kp.prerequisites_json,kp.next_review_at,
                       COUNT(a.id) AS attempts,COALESCE(SUM(a.is_correct),0) AS correct,
                       MAX(a.created_at) AS last_practiced
                FROM knowledge_points kp LEFT JOIN attempts a ON a.knowledge_point_id=kp.id
                GROUP BY kp.id,kp.domain,kp.title,kp.summary,kp.prerequisites_json,kp.next_review_at
                ORDER BY kp.id
                """, (rs, row) -> topicSummary(rs));
    }

    public List<RecentAttempt> recentAttempts(int limit) {
        return jdbc.query("""
                SELECT a.id,a.answer_json,a.score,a.is_correct,a.created_at,q.stem,q.question_type,
                       kp.title AS topic,kp.domain,a.assessment_mode,a.self_assessment
                FROM attempts a JOIN questions q ON q.id=a.question_id
                JOIN knowledge_points kp ON kp.id=a.knowledge_point_id
                ORDER BY a.created_at DESC,a.seq DESC LIMIT ?
                """, (rs, row) -> recentAttempt(rs), limit);
    }

    public List<HistoryAttempt> history(int limit) {
        return jdbc.query("""
                SELECT a.id,a.answer_json,a.score,a.is_correct,a.created_at,q.stem,q.question_type,
                       kp.title AS topic,kp.domain,a.assessment_mode,a.self_assessment
                FROM attempts a JOIN questions q ON q.id=a.question_id
                JOIN knowledge_points kp ON kp.id=a.knowledge_point_id
                ORDER BY a.created_at DESC,a.seq DESC LIMIT ?
                """, (rs, row) -> new HistoryAttempt(rs.getString("id"),
                json.readTree(rs.getString("answer_json")), rs.getDouble("score"),
                rs.getInt("is_correct") != 0, rs.getString("created_at"), rs.getString("stem"),
                rs.getString("question_type"), rs.getString("topic"), rs.getString("domain"),
                rs.getString("assessment_mode"), (Boolean) rs.getObject("self_assessment")), limit);
    }

    public PracticeQuestion nextQuestion(String pointId) {
        String current = now();
        // Aggregate attempts once for the whole candidate set. The previous correlated
        // subqueries executed an attempts lookup per question, which became expensive
        // as the imported question bank grew into the thousands.
        String pointFilter = pointId == null ? "" : "WHERE q.knowledge_point_id=?";
        String sql = """
                WITH candidate_questions AS (
                    SELECT q.id,q.knowledge_point_id,q.question_type,q.stem,q.options_json,q.status,
                           kp.title AS topic_title,kp.domain,kp.summary AS topic_summary,kp.next_review_at,
                           q.source_material_id,q.source_name,q.source_url,q.source_path,q.license,
                           q.attribution,q.revision,q.source_sha256,q.source_locale,q.content_format,q.assessment_mode,
                           COALESCE(NULLIF(m.source_type,''),NULLIF(q.source_name,''),'本地题库') AS source_type
                    FROM questions q
                    JOIN knowledge_points kp ON kp.id=q.knowledge_point_id
                    LEFT JOIN materials m ON m.id=q.source_material_id
                    """ + pointFilter + """
                ), question_attempts AS (
                    SELECT a.question_id, COUNT(*) AS question_attempts
                    FROM attempts a JOIN candidate_questions cq ON cq.id=a.question_id
                    GROUP BY a.question_id
                ), latest_point_attempt AS (
                    SELECT ranked.knowledge_point_id,ranked.is_correct
                    FROM (
                        SELECT a.knowledge_point_id,a.is_correct,
                               ROW_NUMBER() OVER (
                                   PARTITION BY a.knowledge_point_id
                                   ORDER BY a.created_at DESC,a.seq DESC
                               ) AS attempt_rank
                        FROM attempts a
                        JOIN (SELECT DISTINCT knowledge_point_id FROM candidate_questions) cp
                          ON cp.knowledge_point_id=a.knowledge_point_id
                    ) ranked
                    WHERE ranked.attempt_rank=1
                )
                SELECT cq.id,cq.knowledge_point_id,cq.question_type,cq.stem,cq.options_json,cq.status,
                       cq.topic_title,cq.domain,cq.topic_summary,cq.source_material_id,cq.source_name,
                       cq.source_url,cq.source_path,cq.license,cq.attribution,cq.revision,cq.source_sha256,
                       cq.source_locale,cq.content_format,cq.assessment_mode,cq.source_type,
                       CASE
                         WHEN (cq.next_review_at IS NULL AND lpa.is_correct IS NOT NULL
                               OR cq.next_review_at<=?) THEN 0
                         WHEN lpa.is_correct=0 THEN 1
                         WHEN lpa.is_correct IS NULL THEN 2
                         ELSE 3
                       END AS review_priority,
                       COALESCE(qa.question_attempts,0) AS question_attempts
                FROM candidate_questions cq
                LEFT JOIN question_attempts qa ON qa.question_id=cq.id
                LEFT JOIN latest_point_attempt lpa
                       ON lpa.knowledge_point_id=cq.knowledge_point_id
                ORDER BY review_priority,question_attempts,cq.id LIMIT 1
                """;
        List<PracticeQuestion> result;
        if (pointId == null) {
            result = jdbc.query(sql, (rs, row) -> practiceQuestion(rs), current);
        } else {
            result = jdbc.query(sql, (rs, row) -> practiceQuestion(rs), pointId, current);
        }
        return result.stream().findFirst().orElse(null);
    }

    private PracticeQuestion practiceQuestion(ResultSet rs) throws SQLException {
        String[] reasons = {"已到复习时间", "上次回答有遗漏，建议再练", "尚未练习这个知识点", "巩固已经练习过的知识点"};
        return new PracticeQuestion(rs.getString("id"), rs.getString("knowledge_point_id"),
                rs.getString("topic_title"), rs.getString("domain"), rs.getString("topic_summary"),
                rs.getString("question_type"), rs.getString("stem"), json.strings(rs.getString("options_json")),
                rs.getString("source_type"), rs.getString("source_material_id"), rs.getString("source_name"),
                rs.getString("source_url"), rs.getString("source_path"), rs.getString("license"),
                rs.getString("attribution"), rs.getString("revision"), rs.getString("source_sha256"),
                rs.getString("source_locale"), rs.getString("content_format"), rs.getString("assessment_mode"), rs.getString("status"),
                reasons[rs.getInt("review_priority")]);
    }

    public QuestionData question(String questionId) {
        List<QuestionData> result = jdbc.query("""
                SELECT q.id,q.knowledge_point_id,q.question_type,q.stem,q.options_json,q.answer_json,
                       q.explanation,q.rubric_json,q.status,kp.title AS topic_title,
                       kp.domain,kp.summary AS topic_summary,q.source_material_id,q.source_name,q.source_url,
                       q.source_path,q.license,q.attribution,q.revision,q.source_sha256,q.source_locale,q.content_format,q.assessment_mode
                FROM questions q JOIN knowledge_points kp ON kp.id=q.knowledge_point_id WHERE q.id=?
                """, (rs, row) -> new QuestionData(rs.getString("id"), rs.getString("knowledge_point_id"),
                rs.getString("question_type"), rs.getString("stem"), json.strings(rs.getString("options_json")),
                json.readTree(rs.getString("answer_json")), rs.getString("explanation"),
                json.strings(rs.getString("rubric_json")), rs.getString("status"),
                rs.getString("topic_title"), rs.getString("domain"), rs.getString("topic_summary"),
                rs.getString("source_material_id"), rs.getString("source_name"), rs.getString("source_url"),
                rs.getString("source_path"), rs.getString("license"), rs.getString("attribution"),
                rs.getString("revision"), rs.getString("source_sha256"), rs.getString("source_locale"), rs.getString("content_format"),
                rs.getString("assessment_mode")), questionId);
        return result.stream().findFirst().orElse(null);
    }

    public QuestionReference reference(QuestionData question) {
        String selfPrompt = "reference".equals(question.assessment_mode()) ? "对照原始答案，我的回答覆盖了主要内容" : null;
        return new QuestionReference(question.answer(), question.explanation(), question.rubric(), question.topic_title(),
                question.assessment_mode(), selfPrompt, question.source_material_id(), question.source_name(), question.source_url(),
                question.source_path(), question.license(), question.attribution(), question.revision(),
                question.source_sha256(), question.source_locale(), question.content_format());
    }

    public void lockKnowledgePoint(String pointId) {
        jdbc.query("SELECT id FROM knowledge_points WHERE id=? FOR UPDATE", rs -> {
            if (rs.next()) return rs.getString(1);
            return null;
        }, pointId);
    }

    public void insertAttempt(QuestionData question, Object answer, double score, boolean correct,
                              List<Boolean> hits, String createdAt, Boolean selfAssessment) {
        jdbc.update("""
                INSERT INTO attempts(id,question_id,knowledge_point_id,answer_json,score,is_correct,rubric_hits_json,created_at,
                                     assessment_mode,self_assessment)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), question.id(), question.knowledge_point_id(),
                json.write(answer), score, correct ? 1 : 0, json.write(hits), createdAt, question.assessment_mode(), selfAssessment);
    }

    public int correctStreak(String pointId) {
        List<Integer> rows = jdbc.query("""
                SELECT is_correct FROM attempts WHERE knowledge_point_id=?
                ORDER BY created_at DESC,seq DESC LIMIT 20
                """, (rs, row) -> rs.getInt(1), pointId);
        int streak = 0;
        for (Integer correct : rows) {
            if (correct == 0) break;
            streak++;
        }
        return streak;
    }

    public void scheduleReview(String pointId, String reviewAt) {
        jdbc.update("UPDATE knowledge_points SET next_review_at=? WHERE id=?", reviewAt, pointId);
    }

    public boolean hasKnowledgePoint(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_points WHERE id=?)", Boolean.class, id));
    }

    public void insertTopic(String id, String domain, String title, String summary, List<String> prerequisites, String createdAt) {
        jdbc.update("INSERT INTO knowledge_points(id,domain,title,summary,prerequisites_json,created_at) VALUES(?,?,?,?,?,?)",
                id, domain, title, summary, json.write(prerequisites), createdAt);
    }

    public void insertQuestion(String id, String pointId, String type, String stem, List<String> options,
                               Object answer, String explanation, List<String> rubric, String materialId,
                               String sourceName, String sourceUrl, String sourcePath, String license,
                               String attribution, String revision, String sourceSha256,
                               String sourceLocale, String contentFormat, String assessmentMode, String status) {
        jdbc.update("""
                INSERT INTO questions(id,knowledge_point_id,question_type,stem,options_json,answer_json,
                                      explanation,rubric_json,source_material_id,source_name,source_url,
                                      source_path,license,attribution,revision,source_sha256,source_locale,content_format,
                                      assessment_mode,status)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, pointId, type, stem, json.write(options), json.write(answer), explanation,
                json.write(rubric), materialId, sourceName, sourceUrl, sourcePath, license, attribution,
                revision, sourceSha256, sourceLocale, contentFormat, assessmentMode, status);
    }

    public boolean hasQuestion(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM questions WHERE id=?)", Boolean.class, id));
    }

    private String now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private int compareNullFirst(String left, String right) {
        if (left == null) return right == null ? 0 : -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }
}
