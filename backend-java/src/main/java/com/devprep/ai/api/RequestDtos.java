package com.devprep.ai.api;

import java.util.List;

public final class RequestDtos {
    private RequestDtos() { }
    public record Attempt(String question_id, String answer, List<Boolean> rubric_hits, Boolean self_assessment) { }
    public record MaterialImport(String filename, String content) { }
    public record ConfigPayload(String toml) { }
    public record QuestionPayload(String question) { }
}
