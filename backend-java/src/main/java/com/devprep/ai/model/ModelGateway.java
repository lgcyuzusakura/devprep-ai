package com.devprep.ai.model;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Application port; callers do not depend on HTTP or an AI SDK. */
public interface ModelGateway {
    List<String> discover(String providerId, boolean refresh);

    String generate(ModelConfiguration configuration, String question, List<Map<String, Object>> passages);

    default AgentReply agentTurn(ModelConfiguration configuration, String instructions,
                                List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        throw new UnsupportedOperationException("Agent tools are not supported by this adapter");
    }

    default AgentReply agentStream(ModelConfiguration configuration, String instructions,
                                  List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                  Consumer<String> delta) {
        AgentReply reply = agentTurn(configuration, instructions, messages, tools);
        if (!reply.text().isEmpty()) delta.accept(reply.text());
        return reply;
    }
}
