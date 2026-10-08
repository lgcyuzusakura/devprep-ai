package com.devprep.ai.domain;

import java.util.List;

public record MaterialDetail(Material material, List<MaterialChunk> chunks) {
    public record Material(String id, String filename, String title, String source_type,
                          String imported_at, String content) { }
}
