package com.devprep.ai.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法编码 JSON", ex); }
    }

    public JsonNode readTree(String value) {
        try { return mapper.readTree(value); }
        catch (Exception ex) { throw new IllegalArgumentException("JSON 格式无效", ex); }
    }

    public List<String> strings(String value) {
        JsonNode node = readTree(value);
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        node.forEach(item -> { if (item.isTextual()) result.add(item.textValue()); });
        return result;
    }

    public List<Boolean> booleans(String value) {
        JsonNode node = readTree(value);
        if (!node.isArray()) return List.of();
        List<Boolean> result = new ArrayList<>();
        node.forEach(item -> { if (item.isBoolean()) result.add(item.booleanValue()); });
        return result;
    }

    public String array(List<?> values) { return write(values == null ? Collections.emptyList() : values); }
}
