package com.devprep.ai.api;

import com.devprep.ai.model.ModelGateway;
import com.devprep.ai.model.ModelSettingsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ModelController {
    private final ModelSettingsService settings;
    private final ModelGateway gateway;

    public ModelController(ModelSettingsService settings, ModelGateway gateway) {
        this.settings = settings;
        this.gateway = gateway;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        if (!com.devprep.ai.auth.WorkspaceContext.isAdmin()) {
            var configuration = settings.load();
            return Map.of("model", configuration.model(), "model_provider", configuration.modelProvider(), "read_only", true);
        }
        return settings.publicSnapshot();
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody JsonNode payload) {
        requireAdmin();
        JsonNode toml = payload.isObject() ? payload.get("toml") : null;
        if (toml == null || !toml.isTextual()) throw ApiException.badRequest("invalid_config", "toml 必须是文本。");
        return settings.save(toml.textValue());
    }

    @PostMapping("/settings/import-codex")
    public Map<String, String> importCodex(@RequestBody JsonNode payload) {
        requireAdmin();
        if (!payload.isObject()) throw ApiException.badRequest("invalid_json", "请求内容必须是 JSON 对象。");
        return Map.of("toml", settings.importCodexConfig());
    }

    @GetMapping("/models")
    public Map<String, Object> models(@RequestParam(defaultValue = "") String provider,
                                     @RequestParam(defaultValue = "0") String refresh) {
        requireAdmin();
        return Map.of("provider", provider, "models", gateway.discover(provider, refresh.equals("1")));
    }
    private static void requireAdmin() {
        if (!com.devprep.ai.auth.WorkspaceContext.isAdmin()) throw new ApiException(403,"admin_required","此操作仅管理员可用。");
    }
}
