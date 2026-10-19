package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.data.LearningRepository;
import com.devprep.ai.domain.AttemptResult;
import com.devprep.ai.domain.Dashboard;
import com.devprep.ai.domain.HistoryAttempt;
import com.devprep.ai.domain.PracticeQuestion;
import com.devprep.ai.domain.PracticeQueue;
import com.devprep.ai.domain.QuestionData;
import com.devprep.ai.domain.QuestionReference;
import com.devprep.ai.domain.TopicSummary;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class LearningService {
    private final LearningRepository repository;
    private final Clock clock;

    public LearningService(LearningRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() { return repository.dashboard(); }

    @Transactional(readOnly = true)
    public List<TopicSummary> knowledge() { return repository.topicSummaries(); }

    @Transactional(readOnly = true)
    public PracticeQuestion nextQuestion(String pointId) {
        PracticeQuestion question = repository.nextQuestion(pointId == null || pointId.isEmpty() ? null : pointId);
        if (question == null) throw ApiException.notFound("question_not_found", "这个知识点暂时没有可练题目。");
        return question;
    }

    @Transactional(readOnly = true)
    public PracticeQueue practiceQueue(String pointId, String domain, String mode, List<String> excluded) {
        return practiceQueue(pointId, domain, null, "all", mode, excluded);
    }

    @Transactional(readOnly = true)
    public List<com.devprep.ai.domain.PracticeBank> practiceBanks() {
        return PracticeBanks.summarize(repository.topicSummaries());
    }

    @Transactional(readOnly = true)
    public PracticeQueue practiceQueue(String pointId, String domain, String bankId, String type, String mode, List<String> excluded) {
        String point = pointId == null || pointId.isBlank() ? null : pointId;
        String scope = domain == null || domain.isBlank() ? null : domain;
        String bank = bankId == null || bankId.isBlank() ? null : bankId;
        if ((point == null ? 0 : 1)+(scope == null ? 0 : 1)+(bank == null ? 0 : 1) > 1)
            throw ApiException.badRequest("invalid_scope", "请选择一个知识点或一个方向。");
        if (bank != null) PracticeBanks.require(bank);
        if (!List.of("all", "single_choice", "short_answer").contains(type)) throw ApiException.badRequest("invalid_question_type", "请选择选择题、问答题或全部题型。");
        if (point != null && (point.length() > 500 || !repository.hasKnowledgePoint(point)))
            throw ApiException.notFound("question_not_found", "找不到这个知识点。");
        if (scope != null && (scope.length() > 100 || repository.topicSummaries().stream().noneMatch(t -> t.domain().equals(scope))))
            throw ApiException.badRequest("invalid_domain", "找不到这个练习方向。");
        if (!List.of("new", "review").contains(mode)) throw ApiException.badRequest("invalid_practice_mode", "请选择未做新题或重练已做题。");
        if (excluded == null || excluded.size() > 200 || excluded.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 500))
            throw ApiException.badRequest("invalid_exclusions", "单轮练习最多排除 200 道题。");
        return repository.practiceQueue(point, scope, bank, type, mode, excluded.stream().distinct().toList());
    }

    @Transactional(readOnly = true)
    public QuestionReference reference(String questionId) {
        QuestionData question = requireQuestion(questionId);
        return repository.reference(question);
    }

    @Transactional(readOnly = true)
    public List<HistoryAttempt> history(String rawLimit) {
        try {
            return repository.history(Math.max(1, Math.min(Integer.parseInt(rawLimit.trim()), 100)));
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("invalid_limit", "limit 必须是数字。");
        }
    }

    @Transactional
    public AttemptResult submitAttempt(String questionId, String answer, List<Boolean> rubricHits) {
        return submitAttempt(questionId, answer, rubricHits, null);
    }

    @Transactional
    public AttemptResult submitAttempt(String questionId, String answer, List<Boolean> rubricHits, Boolean selfAssessment) {
        if (questionId == null || questionId.isBlank()) throw ApiException.badRequest("invalid_question_id", "请选择要提交的题目。");
        QuestionData question = requireQuestion(questionId);
        if (answer == null || answer.isBlank() || answer.codePointCount(0, answer.length()) > 8000) {
            throw ApiException.badRequest("invalid_answer", "答案不能为空，且不能超过 8000 个字符。");
        }
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(answer));
        } catch (CharacterCodingException ex) {
            throw ApiException.badRequest("invalid_answer", "答案包含无效的 Unicode 字符。");
        }
        boolean correct;
        double score;
        List<Boolean> hits;
        String storedAnswer;
        Boolean storedSelfAssessment = null;
        if ("single_choice".equals(question.question_type())) {
            if (!question.options().contains(answer)) throw ApiException.badRequest("invalid_answer", "请选择题目中的一个选项。");
            correct = question.answer() instanceof JsonNode node && node.isTextual() && answer.equals(node.textValue());
            score = correct ? 1.0 : 0.0;
            hits = List.of();
            storedAnswer = answer;
        } else if ("reference".equals(question.assessment_mode())) {
            if (selfAssessment == null) {
                throw ApiException.badRequest("invalid_self_assessment", "请对照原始答案选择是否掌握。");
            }
            storedSelfAssessment = selfAssessment;
            correct = selfAssessment;
            score = correct ? 1.0 : 0.0;
            hits = List.of();
            storedAnswer = answer.strip();
        } else {
            if (rubricHits == null || rubricHits.size() != question.rubric().size() || rubricHits.stream().anyMatch(item -> item == null)) {
                throw ApiException.badRequest("invalid_rubric", "请按参考要点完成自评。");
            }
            hits = List.copyOf(rubricHits);
            long matched = hits.stream().filter(Boolean.TRUE::equals).count();
            score = question.rubric().isEmpty() ? 0.0 : (double) matched / question.rubric().size();
            correct = score >= 0.999;
            storedAnswer = answer.strip();
        }
        // Serializes submissions for a knowledge point so every schedule observes the preceding attempt.
        repository.lockKnowledgePoint(question.knowledge_point_id());
        OffsetDateTime stamp = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0);
        repository.insertAttempt(question, storedAnswer, score, correct, hits,
                stamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), storedSelfAssessment);
        int streak = repository.correctStreak(question.knowledge_point_id());
        int reviewHours = !correct ? 4 : streak <= 1 ? 24 : streak <= 3 ? 72 : 168;
        String reviewAt = stamp.plusHours(reviewHours).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        repository.scheduleReview(question.knowledge_point_id(), reviewAt);
        return new AttemptResult(correct, Math.round(score * 100.0) / 100.0, question.answer(),
                question.explanation(), question.rubric(), reviewAt, question.topic_title(), streak, reviewHours,
                question.assessment_mode(), storedSelfAssessment, question.content_format());
    }

    private QuestionData requireQuestion(String questionId) {
        QuestionData question = repository.question(questionId);
        if (question == null) throw ApiException.notFound("question_not_found", "找不到这道题。");
        return question;
    }
}
