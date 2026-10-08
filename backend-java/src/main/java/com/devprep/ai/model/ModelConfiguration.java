package com.devprep.ai.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable configuration, with provider order retained for the settings form. */
public record ModelConfiguration(String model, String modelProvider, Map<String, ModelProvider> providers) {
    public ModelConfiguration {
        providers = Collections.unmodifiableMap(new LinkedHashMap<>(providers));
    }

    public static ModelConfiguration empty() {
        return new ModelConfiguration("", "", Map.of());
    }
}
