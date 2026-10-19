package com.devprep.ai.domain;

import java.util.List;

public record TopicSummary(String id, String domain, String title, String summary,
                           int attempts, int correct, Double accuracy,
                           String last_practiced, String next_review_at,
                           List<String> prerequisites, int question_count, int answered_question_count,
                           int new_question_count, int choice_count, int answered_choice_count) { }
