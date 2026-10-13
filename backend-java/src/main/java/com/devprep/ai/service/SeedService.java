package com.devprep.ai.service;

import com.devprep.ai.config.DevPrepProperties;
import com.devprep.ai.data.LearningRepository;
import com.devprep.ai.data.MaterialRepository;
import com.devprep.ai.data.SeedResourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class SeedService {
    public static final String BUILTIN_MATERIAL_ID = "material-java-foundations";
    private final LearningRepository learning;
    private final MaterialRepository materials;
    private final MaterialService materialService;
    private final SeedResourceReader reader;
    private final ObjectMapper mapper;
    private final DevPrepProperties properties;
    private final Clock clock;

    public SeedService(LearningRepository learning, MaterialRepository materials, MaterialService materialService,
                       SeedResourceReader reader, ObjectMapper mapper, DevPrepProperties properties, Clock clock) {
        this.learning = learning; this.materials = materials; this.materialService = materialService;
        this.reader = reader; this.mapper = mapper; this.properties = properties; this.clock = clock;
    }

    @Transactional
    public void initialize() {
        List<SeedDocument> documents = new ArrayList<>();
        try {
            documents.add(parseDocument(mapper.readTree(reader.read(properties.getLearningFile(), "classpath:seed/learning.json")), true));
            String local = properties.getLocalLearningFile();
            if (local != null && !local.isBlank()) documents.add(parseDocument(mapper.readTree(reader.read(local, null)), false));
        } catch (IOException ex) { throw new IllegalStateException("题库与资料无法读取，请检查 seed 资源。", ex); }
        List<TopicSeed> topics = new ArrayList<>();
        List<QuestionSeed> questions = new ArrayList<>();
        Set<String> topicIds = new HashSet<>();
        Set<String> questionIds = new HashSet<>();
        for (SeedDocument document : documents) {
            for (TopicSeed topic : document.topics()) {
                if (!topicIds.add(topic.id())) throw invalidSeed("知识点 id 重复：" + topic.id());
                topics.add(topic);
            }
            for (QuestionSeed question : document.questions()) {
                if (!questionIds.add(question.id())) throw invalidSeed("题目 id 重复：" + question.id());
                questions.add(question);
            }
        }
        for (TopicSeed topic : topics) {
            for (String id : topic.prerequisites()) {
                if (!topicIds.contains(id) && !learning.hasKnowledgePoint(id)) throw invalidSeed("前置知识点不存在：" + id);
                if (topic.id().equals(id)) throw invalidSeed("知识点不能把自己作为前置：" + id);
            }
        }
        for (QuestionSeed question : questions) {
            if (!topicIds.contains(question.point()) && !learning.hasKnowledgePoint(question.point())) {
                throw invalidSeed("题目引用的知识点不存在：" + question.id());
            }
            if (question.sourceId() != null && !BUILTIN_MATERIAL_ID.equals(question.sourceId()) && !materials.exists(question.sourceId())) {
                throw invalidSeed("题目引用的资料不存在：" + question.id());
            }
        }
        if (documents.get(0).topics().isEmpty() || documents.get(0).questions().isEmpty()) throw invalidSeed("内置题库不能为空。");
        String markdown;
        try {
            markdown = reader.read(properties.getSeedFile(), "classpath:seed/java-foundations.md");
        } catch (IOException ex) {
            throw new IllegalStateException("内置资料无法读取，请检查 seed 资源。", ex);
        }
        List<MaterialRepository.ChunkPart> chunks = materialService.split(markdown);
        if (chunks.isEmpty()) throw invalidSeed("内置资料需要至少一段正文。");
        String stamp = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        if (!materials.exists(BUILTIN_MATERIAL_ID)) {
            materials.insertMaterial(BUILTIN_MATERIAL_ID, "java-foundations.md", "计算机学习复习资料", "内置资料", stamp, markdown);
            materials.insertChunks(BUILTIN_MATERIAL_ID, chunks);
        }
        // Ordinary seeds are additive. Explicit translations refresh content while
        // retaining source provenance, prerequisites, and the user's learning progress.
        for (TopicSeed topic : topics) {
            if (!learning.hasKnowledgePoint(topic.id())) {
                learning.insertTopic(topic.id(), topic.domain(), topic.title(), topic.summary(), topic.prerequisites(), stamp);
            } else if (topic.translated()) {
                learning.updateTopicContent(topic.id(), topic.domain(), topic.title(), topic.summary());
            }
        }
        for (QuestionSeed question : questions) {
            if (!learning.hasQuestion(question.id())) {
                learning.insertQuestion(question.id(), question.point(), question.type(), question.stem(),
                        question.options(), question.answer(), question.explanation(), question.rubric(), question.sourceId(),
                        question.sourceName(), question.sourceUrl(), question.sourcePath(), question.license(), question.attribution(),
                        question.revision(), question.sourceSha256(), question.sourceLocale(), question.contentLocale(),
                        question.contentFormat(), question.assessmentMode(), question.status());
            } else if (question.translationPending()) {
                // Source language can be Chinese even when its question body is English.
                // A partial translation must hide that existing row until its translation arrives.
                learning.markQuestionTranslationPending(question.id());
            } else if (isChineseLocale(question.contentLocale())
                    && (question.translated() || !learning.hasChineseContent(question.id()))) {
                // A later local translation may reuse the source question ID. Refresh only
                // answerable content so provenance and existing attempts stay stable.
                learning.updateQuestionContent(question.id(), question.type(), question.stem(), question.options(),
                        question.answer(), question.explanation(), question.rubric(), question.contentLocale(),
                        question.contentFormat(), question.assessmentMode(), question.status());
            }
        }
    }

    private SeedDocument parseDocument(JsonNode root, boolean builtin) {
        if (root == null || !root.isObject() || !root.path("topics").isArray() || !root.path("questions").isArray()) {
            throw invalidSeed("必须提供 topics 和 questions 数组。");
        }
        List<TopicSeed> topics = new ArrayList<>();
        for (JsonNode item : root.get("topics")) {
            topics.add(new TopicSeed(text(item, "id"), text(item, "domain"), text(item, "title"),
                    text(item, "summary"), strings(item, "prerequisites"), isTranslation(item)));
        }
        List<QuestionSeed> questions = new ArrayList<>();
        for (JsonNode item : root.get("questions")) {
            String id = text(item, "id");
            String point = text(item, "point");
            String type = text(item, "type");
            List<String> options = strings(item, "options");
            List<String> rubric = strings(item, "rubric");
            JsonNode answer = item.get("answer");
            if (answer == null || !answer.isTextual() || answer.textValue().isBlank()) throw invalidSeed("题目缺少文本答案：" + id);
            String assessment = textOrDefault(item, "assessment_mode", "rubric");
            String contentFormat = textOrDefault(item, "content_format", "markdown");
            String status = textOrDefault(item, "status", builtin ? "reviewed" : "imported");
            if (!Set.of("rubric", "reference").contains(assessment)) throw invalidSeed("assessment_mode 无效：" + id);
            if (!Set.of("reviewed", "imported", "draft").contains(status)) throw invalidSeed("status 无效：" + id);
            if ("single_choice".equals(type)) {
                if (options.size() < 2 || new HashSet<>(options).size() != options.size() || !options.contains(answer.textValue())) {
                    throw invalidSeed("单选题选项或答案无效：" + id);
                }
                if (!"rubric".equals(assessment)) throw invalidSeed("单选题只能使用 rubric 评估：" + id);
            } else if (!"short_answer".equals(type)) {
                throw invalidSeed("题型无效：" + id);
            } else if ("rubric".equals(assessment) && rubric.isEmpty()) {
                throw invalidSeed("简答题必须提供评分要点，或显式设置 assessment_mode=reference：" + id);
            } else if ("reference".equals(assessment) && !rubric.isEmpty()) {
                throw invalidSeed("reference 模式不能附带评分要点：" + id);
            }
            String sourceId = item.has("source_material_id")
                    ? nullableText(item, "source_material_id") : (builtin ? BUILTIN_MATERIAL_ID : null);
            String sourceName = nullableText(item, "source_name");
            String sourceUrl = nullableText(item, "source_url");
            String sourcePath = nullableText(item, "source_path");
            String license = aliasedText(item, "license", "source_license");
            String attribution = aliasedText(item, "attribution", "source_attribution");
            String revision = aliasedText(item, "revision", "source_revision");
            String sourceSha256 = nullableText(item, "source_sha256");
            String sourceLocale = aliasedText(item, "locale", "source_locale");
            String contentLocale = nullableText(item, "content_locale");
            if (contentLocale == null) contentLocale = isChineseLocale(sourceLocale) ? "zh-CN" : sourceLocale;
            boolean translated = isTranslation(item);
            boolean translationPending = "pending".equals(nullableText(item, "translation_status"));
            if (translationPending) contentLocale = "und";
            if (translated && !isChineseLocale(contentLocale)) {
                throw invalidSeed("中文译文必须设置 content_locale=zh-CN：" + id);
            }
            if (sourceId == null && (sourceName == null || sourceUrl == null)) {
                throw invalidSeed("没有资料 ID 的题目必须提供 source_name 和 source_url：" + id);
            }
            if (sourceUrl != null) validateUrl(sourceUrl, id);
            if (sourceSha256 != null && !sourceSha256.matches("(?i)[0-9a-f]{64}")) {
                throw invalidSeed("source_sha256 必须是 64 位十六进制：" + id);
            }
            questions.add(new QuestionSeed(id, point, type, text(item, "stem"), options, answer.textValue(),
                    text(item, "explanation"), rubric, sourceId, sourceName, sourceUrl, sourcePath, license,
                    attribution, revision, sourceSha256, sourceLocale, contentLocale, contentFormat, assessment, status,
                    translated, translationPending));
        }
        return new SeedDocument(List.copyOf(topics), List.copyOf(questions));
    }

    private void validateUrl(String value, String id) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw invalidSeed("source_url 无效：" + id);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw invalidSeed("source_url 必须是无用户信息的 http(s) URL：" + id);
        }
    }

    private String textOrDefault(JsonNode item, String name, String fallback) {
        return item.has(name) ? text(item, name) : fallback;
    }

    private boolean isChineseLocale(String locale) {
        return locale != null && locale.trim().toLowerCase(java.util.Locale.ROOT).startsWith("zh");
    }

    private boolean isTranslation(JsonNode item) {
        return "translated".equals(nullableText(item, "translation_status"));
    }

    private String nullableText(JsonNode item, String name) {
        JsonNode value = item.get(name);
        if (value == null || value.isNull()) return null;
        return text(item, name);
    }

    private String aliasedText(JsonNode item, String name, String alias) {
        String value = nullableText(item, name);
        String aliasValue = nullableText(item, alias);
        if (value != null && aliasValue != null && !value.equals(aliasValue)) throw invalidSeed("来源字段相互冲突：" + name);
        return value == null ? aliasValue : value;
    }

    private String text(JsonNode item, String name) {
        JsonNode value = item.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) throw invalidSeed("字段 " + name + " 必须是非空文本。");
        return value.textValue();
    }

    private List<String> strings(JsonNode item, String name) {
        JsonNode values = item.get(name);
        if (values == null || !values.isArray()) throw invalidSeed("字段 " + name + " 必须是数组。");
        List<String> list = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isBlank()) throw invalidSeed("字段 " + name + " 包含无效文本。");
            list.add(value.textValue());
        }
        return List.copyOf(list);
    }

    private IllegalStateException invalidSeed(String message) { return new IllegalStateException("题库格式无效：" + message); }
    private record SeedDocument(List<TopicSeed> topics, List<QuestionSeed> questions) { }
    private record TopicSeed(String id, String domain, String title, String summary,
                             List<String> prerequisites, boolean translated) { }
    private record QuestionSeed(String id, String point, String type, String stem, List<String> options,
                                String answer, String explanation, List<String> rubric, String sourceId,
                                String sourceName, String sourceUrl, String sourcePath, String license,
                                String attribution, String revision, String sourceSha256, String sourceLocale,
                                String contentLocale, String contentFormat, String assessmentMode, String status,
                                boolean translated, boolean translationPending) { }
}
