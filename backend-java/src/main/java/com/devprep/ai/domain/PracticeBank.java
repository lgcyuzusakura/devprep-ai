package com.devprep.ai.domain;

public record PracticeBank(String id, String title, String description, int question_count,
                           int choice_count, int answered_choice_count, int new_choice_count,
                           int short_answer_count, int topic_count) { }
