package com.devprep.ai;

import com.devprep.ai.service.InterviewExperienceCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InterviewExperienceCatalogTest {
    ObjectMapper mapper=new ObjectMapper();
    @TempDir Path temp;
    @Test void automaticDifficultySeparatesStageAndCompanyStyleAndHonorsOverride() {
        assertEquals("basic",InterviewExperienceCatalog.resolveDifficulty("auto","campus","large"));
        assertEquals("basic",InterviewExperienceCatalog.resolveDifficulty("auto","junior","small"));
        assertEquals("intermediate",InterviewExperienceCatalog.resolveDifficulty("auto","junior","medium"));
        assertEquals("advanced",InterviewExperienceCatalog.resolveDifficulty("auto","senior","general"));
        assertEquals("advanced",InterviewExperienceCatalog.resolveDifficulty("advanced","campus","small"));
        assertTrue(InterviewExperienceCatalog.standard("basic","large").get("criteria").contains("不要求生产经验"));
    }
    @Test void retrievalFiltersDifficultyRanksTechnicalRelevanceAndDoesNotMutateCatalog() throws Exception {
        var catalog=new InterviewExperienceCatalog(mapper,"");
        var input=mapper.createObjectNode().put("role","Go 后端").put("jd","context goroutine").put("difficulty","intermediate");
        var result=catalog.retrieve(input,100);
        assertFalse(result.isEmpty());assertEquals("original-interview-2-intermediate",result.get(0).path("id").asText());
        assertTrue(result.stream().allMatch(q->q.path("difficulty").asText().equals("intermediate")));
        assertEquals(result.size(),result.stream().map(q->q.path("id").asText()).distinct().count());
        ((com.fasterxml.jackson.databind.node.ObjectNode)result.get(0)).put("prompt","被修改");
        assertFalse(catalog.retrieve(input,100).stream().anyMatch(q->q.path("prompt").asText().equals("被修改")));
        assertEquals(48,catalog.metadata().path("total").asInt());
    }
    @Test void pinnedSourceProvenanceLoadsLocallyAndMetadataNeverLeaksQuestionOrAnswer() throws Exception {
        var entry=mapper.createObjectNode().put("id","private-source").put("title","本地私有问题").put("prompt","如何检查私有项目的事务边界？")
                .put("reference","私有参考答案").put("difficulty","basic").put("kind","knowledge").put("source_name","本地候选人回忆")
                .put("source_kind","candidate_recollection").put("source_url","https://example.org/revision/file#L2").put("source_document","file.md").put("source_revision","abc123").put("attribution","候选人署名").put("company","示例企业").put("license","CC-BY-NC-SA-4.0");
        entry.putArray("tags").add("java");entry.putArray("rubric").add("事务边界");
        var pack=mapper.createObjectNode();pack.putArray("experiences").add(entry);Path file=temp.resolve("pack.json");mapper.writeValue(file.toFile(),pack);
        var catalog=new InterviewExperienceCatalog(mapper,file.toString());
        var result=catalog.retrieve(mapper.createObjectNode().put("role","Java").put("difficulty","basic"),100);
        assertTrue(result.stream().anyMatch(q->q.path("attribution").asText().equals("候选人署名")&&q.path("source_revision").asText().equals("abc123")));
        assertEquals(49,catalog.metadata().path("total").asInt());
        assertFalse(catalog.metadata().toString().contains("私有参考答案"));assertFalse(catalog.metadata().toString().contains("本地私有问题"));
    }
    @Test void malformedOrMissingLocalPackFailsExplicitly() throws Exception {
        var file=temp.resolve("bad.json");Files.writeString(file,"{\"experiences\":[{}]}");
        assertThrows(java.io.IOException.class,()->new InterviewExperienceCatalog(mapper,file.toString()));
        assertThrows(java.io.IOException.class,()->new InterviewExperienceCatalog(mapper,temp.resolve("missing.json").toString()));
    }
}
