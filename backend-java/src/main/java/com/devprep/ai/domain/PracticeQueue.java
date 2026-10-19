package com.devprep.ai.domain;

/** Counts refer only to Chinese questions visible in the selected workspace and scope. */
public record PracticeQueue(PracticeQuestion question, String scope_title, String mode,
                            int total_questions, int answered_questions, int remaining_questions) { }
