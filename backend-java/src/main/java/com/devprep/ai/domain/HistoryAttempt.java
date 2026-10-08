package com.devprep.ai.domain;

public record HistoryAttempt(String id, Object answer, double score, boolean is_correct,
                             String created_at, String stem, String question_type,
                             String topic, String domain, String assessment_mode,
                             Boolean self_assessment) { }
