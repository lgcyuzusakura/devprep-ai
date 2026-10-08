package com.devprep.ai.domain;

public record ImportedMaterial(String id, String filename, String title,
                               int chunk_count, String imported_at) { }
