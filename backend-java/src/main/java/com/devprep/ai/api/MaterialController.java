package com.devprep.ai.api;

import com.devprep.ai.domain.ImportedMaterial;
import com.devprep.ai.domain.MaterialDetail;
import com.devprep.ai.domain.MaterialSummary;
import com.devprep.ai.domain.ResourceItem;
import com.devprep.ai.service.MaterialService;
import com.devprep.ai.service.ResourceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MaterialController {
    private final MaterialService materials;
    private final ResourceService resources;
    public MaterialController(MaterialService materials, ResourceService resources) { this.materials = materials; this.resources = resources; }

    @GetMapping("/materials")
    public Map<String, List<MaterialSummary>> list() { return Map.of("materials", materials.list()); }

    @GetMapping("/materials/{id}")
    public MaterialDetail detail(@PathVariable String id) { return materials.detail(id); }

    @PostMapping("/materials/import")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, ImportedMaterial> importMaterial(@RequestBody JsonNode payload) {
        if (!payload.isObject()) throw ApiException.badRequest("invalid_json", "请求内容必须是 JSON 对象。");
        JsonNode filename = payload.get("filename");
        JsonNode content = payload.get("content");
        return Map.of("material", materials.importMaterial(filename == null ? "study.md" : filename.isTextual() ? filename.textValue() : null,
                content != null && content.isTextual() ? content.textValue() : null));
    }

    @DeleteMapping("/materials/{id}")
    public Map<String, Object> delete(@PathVariable String id) { return materials.deleteMaterial(id); }

    @GetMapping("/resources")
    public Map<String, List<ResourceItem>> resources() { return Map.of("resources", resources.list()); }
}
