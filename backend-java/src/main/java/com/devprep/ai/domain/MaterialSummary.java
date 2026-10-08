package com.devprep.ai.domain;

public record MaterialSummary(String id, String filename, String title, String source_type,
                              String imported_at, int chunk_count) { }
