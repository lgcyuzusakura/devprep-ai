package com.devprep.ai.domain;

import java.util.List;

public record QuestionReference(Object answer, String explanation, List<String> rubric,
                               String topic_title, String assessment_mode,
                               String self_assessment_prompt,
                               String source_material_id, String source_name, String source_url,
                               String source_path, String source_license, String source_attribution,
                               String source_revision, String source_sha256, String source_locale, String content_format) { }
