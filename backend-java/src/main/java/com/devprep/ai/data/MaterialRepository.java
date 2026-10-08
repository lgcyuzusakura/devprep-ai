package com.devprep.ai.data;

import com.devprep.ai.domain.ChunkHit;
import com.devprep.ai.domain.MaterialChunk;
import com.devprep.ai.domain.MaterialDetail;
import com.devprep.ai.domain.MaterialSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class MaterialRepository {
    private final JdbcTemplate jdbc;
    private static final Pattern LATIN = Pattern.compile("[a-z0-9_+#.]{2,}");
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u9fff]+");
    private static final Set<String> STOP = Set.of("what", "is", "are", "how", "why", "the", "does", "explain", "of", "to", "and",
            "什么", "如何", "为何", "怎样", "怎么", "请问", "介绍", "一下", "解释", "简述");

    public MaterialRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<MaterialSummary> list() {
        return jdbc.query("""
                SELECT m.id,m.filename,m.title,m.source_type,m.imported_at,COUNT(c.id) chunk_count
                FROM materials m LEFT JOIN material_chunks c ON c.material_id=m.id
                GROUP BY m.id,m.filename,m.title,m.source_type,m.imported_at ORDER BY m.imported_at DESC
                """, (rs, row) -> new MaterialSummary(rs.getString("id"), rs.getString("filename"),
                rs.getString("title"), rs.getString("source_type"), rs.getString("imported_at"), rs.getInt("chunk_count")));
    }

    public MaterialDetail detail(String id) {
        List<MaterialDetail.Material> materials = jdbc.query("SELECT id,filename,title,source_type,imported_at,content FROM materials WHERE id=?",
                (rs, row) -> new MaterialDetail.Material(rs.getString("id"), rs.getString("filename"),
                        rs.getString("title"), rs.getString("source_type"), rs.getString("imported_at"), rs.getString("content")), id);
        if (materials.isEmpty()) return null;
        List<MaterialChunk> chunks = jdbc.query("SELECT id,chunk_index,heading,content FROM material_chunks WHERE material_id=? ORDER BY chunk_index",
                (rs, row) -> new MaterialChunk(rs.getString("id"), rs.getInt("chunk_index"), rs.getString("heading"), rs.getString("content")), id);
        return new MaterialDetail(materials.get(0), chunks);
    }

    public void insertMaterial(String id, String filename, String title, String sourceType, String importedAt, String content) {
        jdbc.update("INSERT INTO materials(id,filename,title,source_type,imported_at,content) VALUES(?,?,?,?,?,?)",
                id, filename, title, sourceType, importedAt, content);
    }

    public int insertChunks(String materialId, List<ChunkPart> parts) {
        int index = 0;
        for (ChunkPart part : parts) {
            jdbc.update("INSERT INTO material_chunks(id,material_id,chunk_index,heading,content) VALUES(?,?,?,?,?)",
                    materialId + ":" + index, materialId, index++, part.heading(), part.content());
        }
        return index;
    }

    public boolean exists(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM materials WHERE id=?)", Boolean.class, id));
    }

    public String lockSourceType(String id) {
        List<String> values = jdbc.query("SELECT source_type FROM materials WHERE id=? FOR UPDATE", (rs, row) -> rs.getString(1), id);
        return values.stream().findFirst().orElse(null);
    }

    public boolean isReferenced(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM questions WHERE source_material_id=?)", Boolean.class, id));
    }

    public void delete(String id) { jdbc.update("DELETE FROM materials WHERE id=?", id); }

    public List<ChunkHit> search(String query, int limit) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) return List.of();
        Set<String> terms = new java.util.HashSet<>();
        Matcher latin = LATIN.matcher(normalized);
        while (latin.find()) if (!STOP.contains(latin.group())) terms.add(latin.group());
        Matcher han = HAN.matcher(normalized);
        while (han.find()) {
            String block = han.group();
            if (block.length() == 1 && !STOP.contains(block)) terms.add(block);
            for (int i = 0; i + 1 < block.length(); i++) {
                String term = block.substring(i, i + 2);
                if (!STOP.contains(term)) terms.add(term);
            }
        }
        if (terms.isEmpty()) terms.add(normalized);
        List<ChunkHit> hits = new ArrayList<>();
        jdbc.query("""
                SELECT c.id,c.material_id,c.heading,c.content,m.filename,m.title,m.source_type
                FROM material_chunks c JOIN materials m ON m.id=c.material_id
                """, rs -> {
            String heading = rs.getString("heading");
            String content = rs.getString("content");
            String text = (heading + "\n" + content).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) score += Math.min(count(text, term), 3);
            if (score > 0) hits.add(new ChunkHit(rs.getString("id"), rs.getString("material_id"),
                    rs.getString("filename"), rs.getString("title"), rs.getString("source_type"), heading, content, score));
        });
        hits.sort(Comparator.comparingInt(ChunkHit::score).reversed().thenComparing(ChunkHit::filename).thenComparing(ChunkHit::heading));
        return hits.stream().limit(Math.max(1, Math.min(limit, 10))).toList();
    }

    private int count(String text, String term) {
        int at = 0, count = 0;
        while (count < 3 && (at = text.indexOf(term, at)) >= 0) { count++; at += Math.max(1, term.length()); }
        return count;
    }

    public record ChunkPart(String heading, String content) { }
}
