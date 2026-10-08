package com.devprep.ai.domain;

import java.util.List;

public record AttemptResult(boolean correct, double score, Object answer, String explanation,
                            List<String> rubric, String review_at, String topic_title,
                            int streak, int review_hours, String assessment_mode,
                            Boolean self_assessment, String content_format) { }
