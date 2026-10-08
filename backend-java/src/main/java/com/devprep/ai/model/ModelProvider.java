package com.devprep.ai.model;

/** The non-secret settings needed by an OpenAI-compatible provider. */
public record ModelProvider(
        String id,
        String name,
        String baseUrl,
        String wireApi,
        String envKey,
        String modelsPath,
        int timeoutSeconds) {

    /** Keep the gateway prefix: /v1 plus /models is /v1/models. */
    public String endpoint(String path) {
        return baseUrl + "/" + path.replaceFirst("^/+", "");
    }
}
