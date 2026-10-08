package com.devprep.ai.domain;

import java.util.List;

public record TopicSummary(String id, String domain, String title, String summary,
                           int attempts, int correct, Double accuracy,
                           String last_practiced, String next_review_at,
                           List<String> prerequisites) { }
