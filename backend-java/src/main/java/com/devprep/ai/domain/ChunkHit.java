package com.devprep.ai.domain;

public record ChunkHit(String id, String material_id, String filename, String title,
                       String source_type, String heading, String content, int score) { }
