package com.devprep.ai.domain;

import java.util.List;

public record Dashboard(String date, int studied_today, int total_attempts, int due_count,
                        int topic_count, int question_count, List<TopicSummary> due_topics,
                        List<TopicSummary> topics, List<RecentAttempt> recent_attempts) { }
