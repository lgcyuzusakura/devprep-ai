package com.devprep.ai.domain;

import java.util.List;

public record QaResult(String answer, List<Citation> citations, String model_used, boolean grounded) {
    public record Citation(String id, String material_id, String title, String heading,
                           String source_type, String excerpt, int score) { }
}
