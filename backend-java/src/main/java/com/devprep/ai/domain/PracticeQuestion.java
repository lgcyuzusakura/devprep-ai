package com.devprep.ai.domain;

import java.util.List;

public record PracticeQuestion(String id, String knowledge_point_id, String topic_title,
                               String domain, String topic_summary, String question_type,
                               String stem, List<String> options, String source_type,
                               String source_material_id, String source_name, String source_url,
                               String source_path, String source_license, String source_attribution,
                               String source_revision, String source_sha256, String source_locale, String content_format,
                               String assessment_mode,
                               String status, String reason) { }
