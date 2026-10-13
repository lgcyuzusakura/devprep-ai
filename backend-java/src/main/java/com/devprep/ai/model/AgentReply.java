package com.devprep.ai.model;

import java.util.List;

public record AgentReply(String text, List<ToolCall> calls) {
    public record ToolCall(String id, String name, String arguments) { }
}
