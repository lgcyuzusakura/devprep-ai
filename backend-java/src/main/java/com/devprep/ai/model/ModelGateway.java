package com.devprep.ai.model;

import java.util.List;
import java.util.Map;

/** Application port; callers do not depend on HTTP or an AI SDK. */
public interface ModelGateway {
    List<String> discover(String providerId, boolean refresh);

    String generate(ModelConfiguration configuration, String question, List<Map<String, Object>> passages);
}
