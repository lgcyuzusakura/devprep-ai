package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.domain.ChunkHit;
import com.devprep.ai.domain.QaResult;
import com.devprep.ai.model.ModelConfiguration;
import com.devprep.ai.model.ModelGateway;
import com.devprep.ai.model.ModelSettingsService;
import org.springframework.stereotype.Service;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QaService {
    private static final Pattern SOURCE_NUMBER = Pattern.compile("\\[(\\d+)]");
    private final MaterialService materials;
    private final ModelSettingsService settings;
    private final ModelGateway gateway;

    public QaService(MaterialService materials, ModelSettingsService settings, ModelGateway gateway) {
        this.materials = materials;
        this.settings = settings;
        this.gateway = gateway;
    }

    public QaResult answer(String question) {
        if (question == null || question.isBlank() || question.codePointCount(0, question.length()) > 4000) {
            throw ApiException.badRequest("invalid_question", "问题不能为空，且不能超过 4000 个字符。");
        }
        try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(question));
        } catch (CharacterCodingException ex) {
            throw ApiException.badRequest("invalid_question", "问题包含无效的 Unicode 字符。");
        }
        String query = question.strip();
        List<ChunkHit> hits = materials.search(query, 6);
        if (hits.isEmpty()) {
            return new QaResult("资料库中没有找到足够相关的内容。请导入课程笔记或限定问题范围后再试。",
                    List.of(), null, false);
        }
        List<QaResult.Citation> citations = hits.stream().map(hit -> new QaResult.Citation(
                hit.id(), hit.material_id(), hit.title(), hit.heading(), hit.source_type(),
                excerpt(hit.content()), hit.score())).toList();
        List<Map<String, Object>> passages = new ArrayList<>();
        for (ChunkHit hit : hits) {
            passages.add(Map.of("id", hit.id(), "material_id", hit.material_id(), "title", hit.title(),
                    "heading", hit.heading(), "source_type", hit.source_type(), "content", hit.content()));
        }
        ModelConfiguration config = settings.load();
        String text;
        try {
            text = gateway.generate(config, query, passages);
        } catch (ApiException ex) {
            if (!"model_not_configured".equals(ex.getCode())) throw ex;
            return new QaResult("当前模型尚未配置。先阅读下方命中的原文资料；配置模型后可以生成带来源编号的解释。",
                    citations, null, false);
        }
        return new QaResult(text, citations, config.model(), validReferences(text, hits.size()));
    }

    private static String excerpt(String content) {
        int count = content.codePointCount(0, content.length());
        return count <= 900 ? content : content.substring(0, content.offsetByCodePoints(0, 900));
    }

    // This checks source numbering only. It cannot prove that the source entails the answer.
    static boolean validReferences(String answer, int count) {
        Matcher numbers = SOURCE_NUMBER.matcher(answer);
        boolean found = false;
        while (numbers.find()) {
            found = true;
            int number;
            try { number = Integer.parseInt(numbers.group(1)); }
            catch (NumberFormatException ex) { return false; }
            if (number < 1 || number > count) return false;
        }
        return found;
    }
}
