package com.devprep.ai.data;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import com.devprep.ai.domain.ResourceItem;
import com.devprep.ai.service.ResourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class ResourceRepositoryTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private DevPrepProperties properties;
    private ResourceService resources;

    @BeforeEach
    void setUp() {
        properties = new DevPrepProperties();
        properties.setResourcesFile(temporary.resolve("resources.json").toString());
        resources = new ResourceService(new ResourceRepository(
                new SeedResourceReader(new DefaultResourceLoader()), mapper, properties));
    }

    @Test
    void readsConfiguredFilesystemPathAndClosesFileSoItCanBeReplaced() throws Exception {
        Path file = Path.of(properties.getResourcesFile());
        Files.writeString(file, mapper.writeValueAsString(Map.of("resources", List.of(validItem()))));
        assertEquals("fixture", resources.list().get(0).id());
        Files.writeString(file, "[]");
        assertEquals(List.of(), resources.list());
        Files.delete(file);
        assertError("resources_unreadable");
    }

    @Test
    void missingMalformedAndNonUtf8FilesAreUnreadable() throws Exception {
        assertError("resources_unreadable");
        Path file = Path.of(properties.getResourcesFile());
        Files.writeString(file, "{ malformed json");
        assertError("resources_unreadable");
        Files.write(file, new byte[]{(byte) 0xc0, (byte) 0xaf});
        assertError("resources_unreadable");
    }

    @Test
    void invalidShapeFieldsAndUrlsHaveDistinctErrorFromUnreadableFiles() throws Exception {
        Path file = Path.of(properties.getResourcesFile());
        for (Object invalid : List.of(Map.of("wrong", List.of()), List.of(123),
                List.of(Map.of("id", "partial")), List.of(withUrl("file:///C:/example")),
                List.of(withUrl("https://")), List.of(withUrl("not a uri")))) {
            Files.writeString(file, mapper.writeValueAsString(invalid));
            assertError("resources_invalid");
        }
    }

    @Test
    void classpathCatalogContainsTheRequestedLearningTracksAndUsableMetadata() {
        properties.setResourcesFile("classpath:seed/resources.json");
        List<ResourceItem> catalog = resources.list();
        Set<String> tracks = catalog.stream().map(ResourceItem::track).collect(Collectors.toSet());
        assertTrue(tracks.containsAll(Set.of("前端", "Java", "后端", "Go", "Python", "AI", "Agent", "软考")));
        assertTrue(tracks.stream().anyMatch(track -> track.contains("考研")));
        for (ResourceItem item : catalog) {
            assertFalse(item.description().isBlank());
            assertFalse(item.license().isBlank());
            assertFalse(item.source().isBlank());
            assertTrue(item.url().startsWith("https://") || item.url().startsWith("http://"));
        }
    }

    private Map<String, String> validItem() {
        return Map.of("id", "fixture", "track", "Java", "title", "Fixture",
                "kind", "参考索引", "description", "A curated source", "url", "https://example.com/course",
                "license", "查看上游许可", "source", "Example");
    }

    private Map<String, String> withUrl(String url) {
        var item = new java.util.HashMap<>(validItem());
        item.put("url", url);
        return item;
    }

    private void assertError(String code) {
        ApiException error = assertThrows(ApiException.class, resources::list);
        assertEquals(500, error.getStatus());
        assertEquals(code, error.getCode());
    }
}
