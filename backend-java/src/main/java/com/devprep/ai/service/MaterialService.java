package com.devprep.ai.service;

import com.devprep.ai.api.ApiException;
import com.devprep.ai.config.DevPrepProperties;
import com.devprep.ai.data.MaterialRepository;
import com.devprep.ai.data.MaterialRepository.ChunkPart;
import com.devprep.ai.domain.ChunkHit;
import com.devprep.ai.domain.ImportedMaterial;
import com.devprep.ai.domain.MaterialDetail;
import com.devprep.ai.domain.MaterialSummary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MaterialService {
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}[ \\t]+(.+?)(?:[ \\t]+#+)?[ \\t]*$");
    private final MaterialRepository repository;
    private final DevPrepProperties properties;
    private final Clock clock;

    public MaterialService(MaterialRepository repository, DevPrepProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MaterialSummary> list() { return repository.list(); }

    @Transactional(readOnly = true)
    public MaterialDetail detail(String id) {
        MaterialDetail detail = repository.detail(id);
        if (detail == null) throw ApiException.notFound("material_not_found", "找不到这份资料。");
        return detail;
    }

    @Transactional
    public ImportedMaterial importMaterial(String filename, String content) {
        if (filename == null || content == null) throw ApiException.badRequest("invalid_material", "文件名和 content 必须是文本。");
        int contentBytes = utf8Bytes(content);
        utf8Bytes(filename);
        String safeName = filename.replace('\\', '/');
        safeName = safeName.substring(safeName.lastIndexOf('/') + 1);
        safeName = truncate(safeName, 120);
        int dot = safeName.lastIndexOf('.');
        String extension = dot < 0 ? "" : safeName.substring(dot).toLowerCase(java.util.Locale.ROOT);
        if (!List.of(".md", ".markdown", ".txt").contains(extension)) {
            throw ApiException.badRequest("unsupported_file", "目前支持 Markdown 和纯文本资料。");
        }
        if (content.isBlank()) throw ApiException.badRequest("empty_material", "文件内容为空。");
        if (contentBytes > properties.getMaxBodyBytes()) throw new ApiException(413, "material_too_large", "单份资料不能超过 1 MB。");
        List<ChunkPart> chunks = split(content);
        if (chunks.isEmpty()) throw ApiException.badRequest("empty_material", "资料需要至少包含一段正文。");
        String title = safeName.substring(0, dot).replace('_', ' ').replace('-', ' ');
        String id = "material-" + UUID.randomUUID().toString().replace("-", "");
        String stamp = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        repository.insertMaterial(id, safeName, title, "用户导入", stamp, content);
        int count = repository.insertChunks(id, chunks);
        return new ImportedMaterial(id, safeName, title, count, stamp);
    }

    @Transactional
    public Map<String, Object> deleteMaterial(String id) {
        String sourceType = repository.lockSourceType(id);
        if (sourceType == null) throw ApiException.notFound("material_not_found", "找不到这份资料。");
        if (!"用户导入".equals(sourceType)) throw new ApiException(403, "protected_material", "内置资料不能删除。");
        if (repository.isReferenced(id)) throw new ApiException(409, "material_in_use", "这份资料仍被题目引用，不能删除。");
        try { repository.delete(id); }
        catch (DataIntegrityViolationException ex) { throw new ApiException(409, "material_in_use", "这份资料仍被题目引用，不能删除。"); }
        return Map.of("deleted", true, "id", id);
    }

    @Transactional(readOnly = true)
    public List<ChunkHit> search(String query, int limit) { return repository.search(query, limit); }

    public List<ChunkPart> split(String content) {
        List<ChunkPart> chunks = new ArrayList<>();
        String heading = "未分类";
        StringBuilder lines = new StringBuilder();
        char fenceMarker = 0;
        int fenceLength = 0;
        for (String line : content.split("\\R", -1)) {
            Matcher fence = FENCE.matcher(line);
            if (fence.find()) {
                String marker = fence.group(1);
                if (fenceMarker == 0) { fenceMarker = marker.charAt(0); fenceLength = marker.length(); }
                else if (marker.charAt(0) == fenceMarker && marker.length() >= fenceLength) { fenceMarker = 0; fenceLength = 0; }
                lines.append(line).append('\n');
                continue;
            }
            Matcher match = HEADING.matcher(line);
            if (fenceMarker == 0 && match.matches()) {
                appendChunk(chunks, heading, lines);
                heading = match.group(1).strip();
                lines.setLength(0);
            } else lines.append(line).append('\n');
        }
        appendChunk(chunks, heading, lines);
        return chunks;
    }

    private void appendChunk(List<ChunkPart> chunks, String heading, StringBuilder text) {
        String content = text.toString().strip();
        if (!content.isEmpty()) chunks.add(new ChunkPart(heading, content));
    }

    private int utf8Bytes(String value) {
        try {
            return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)).remaining();
        } catch (CharacterCodingException ex) {
            throw ApiException.badRequest("invalid_material", "资料包含无效的 Unicode 字符。");
        }
    }

    private String truncate(String value, int codePoints) {
        if (value.codePointCount(0, value.length()) <= codePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, codePoints));
    }
}
