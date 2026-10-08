package com.devprep.ai.domain;

public record ResourceItem(String id, String track, String title, String kind,
                           String description, String url, String license, String source) { }
