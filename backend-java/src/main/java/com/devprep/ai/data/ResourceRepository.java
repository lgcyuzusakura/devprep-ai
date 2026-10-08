package com.devprep.ai.data;

import com.devprep.ai.domain.ResourceItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ResourceRepository {
    private final SeedResourceReader reader;
    private final ObjectMapper mapper;
    private final com.devprep.ai.config.DevPrepProperties properties;

    public ResourceRepository(SeedResourceReader reader, ObjectMapper mapper, com.devprep.ai.config.DevPrepProperties properties) {
        this.reader = reader; this.mapper = mapper; this.properties = properties;
    }

    public List<ResourceItem> list() {
        JsonNode root;
        try {
            root = mapper.readTree(reader.read(properties.getResourcesFile(), "classpath:seed/resources.json"));
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("公开资源目录暂时无法读取。", ex);
        }
        JsonNode items = root == null ? null : root.isArray() ? root : root.get("resources");
        if (items == null || !items.isArray()) throw invalid("公开资源目录格式无效。");
        List<ResourceItem> output = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) throw invalid("公开资源目录包含无效条目。");
            String[] fields = {"id", "track", "title", "kind", "description", "url", "license", "source"};
            for (String field : fields) {
                if (!item.hasNonNull(field) || !item.get(field).isTextual() || item.get(field).textValue().isBlank()) {
                    throw invalid("公开资源目录缺少必要字段。");
                }
            }
            String url = item.get("url").textValue().strip();
            try {
                URI uri = URI.create(url);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null) throw invalid("公开资源目录包含无效链接。");
            } catch (IllegalArgumentException ex) { throw invalid("公开资源目录包含无效链接。"); }
            output.add(new ResourceItem(item.get("id").textValue().strip(), item.get("track").textValue().strip(),
                    item.get("title").textValue().strip(), item.get("kind").textValue().strip(), item.get("description").textValue().strip(),
                    url, item.get("license").textValue().strip(), item.get("source").textValue().strip()));
        }
        return List.copyOf(output);
    }

    private InvalidResourceException invalid(String message) { return new InvalidResourceException(message); }
    public static class InvalidResourceException extends IllegalStateException {
        public InvalidResourceException(String message) { super(message); }
    }
}
