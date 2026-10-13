package com.devprep.ai.domain;

import java.util.List;

public record QuestionData(String id, String knowledge_point_id, String question_type,
                           String stem, List<String> options, Object answer, String explanation,
                           List<String> rubric, String status, String topic_title,
                           String domain, String topic_summary, String source_material_id,
                           String source_name, String source_url, String source_path,
                           String license, String attribution, String revision,
                           String source_sha256, String source_locale, String content_locale,
                           String content_format, String assessment_mode) { }
