package com.devprep.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Local retrieval only: no network, embeddings or additional model requests. */
@Service
public class InterviewExperienceCatalog {
    private final Map<String,JsonNode> entries = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    public InterviewExperienceCatalog(ObjectMapper mapper,
            @Value("${devprep.interview-experience-file:}") String localFile) throws IOException {
        this.mapper=mapper;
        try(var stream=new ClassPathResource("seed/interview-experiences.json").getInputStream()) {load(mapper.readTree(stream));}
        if(!localFile.isBlank())try(var stream=Files.newInputStream(Path.of(localFile))) {load(mapper.readTree(stream));}
    }
    private void load(JsonNode pack) throws IOException {
        if(!pack.path("experiences").isArray())throw new IOException("面经文件缺少 experiences 数组");
        for(JsonNode entry:pack.path("experiences")) {
            String id=entry.path("id").asText();
            if(id.isBlank()||!entry.path("prompt").asText().matches("(?s).*[\\p{IsHan}].*")
                    ||!List.of("basic","intermediate","advanced").contains(entry.path("difficulty").asText())
                    ||!List.of("knowledge","algorithm","project").contains(entry.path("kind").asText())
                    ||!entry.path("tags").isArray()||!entry.path("rubric").isArray()
                    ||entry.path("source_name").asText().isBlank())throw new IOException("无效面经条目："+id);
            entries.put(id,entry.deepCopy());
        }
    }
    public static String resolveDifficulty(String selected,String level,String style) {
        if(!selected.equals("auto"))return selected;
        if(List.of("none","campus").contains(level)||style.equals("small"))return "basic";
        return level.equals("senior")?"advanced":"intermediate";
    }
    public static Map<String,String> standard(String difficulty,String style) {
        String criteria=switch(difficulty) {
            case "basic"->"基础：能解释概念、完成简单应用和边界检查；算法以 easy 为主；项目讲清本人职责和完整链路。允许课程或个人项目，不要求生产经验。";
            case "advanced"->"高阶：深入机制、容量与性能、故障时序、系统设计和方案验证；算法 medium/hard；项目要求明确假设、取舍和证据，不替候选人虚构经历。";
            default->"进阶：解释原理并结合场景作取舍、排查故障；算法以 medium 为主；项目能说明失败路径、测试和验证方法。";
        };
        String focus=switch(style) {
            case "small"->"小厂练习场景：侧重交付、完整业务链路、基础扎实和独立排障。";
            case "medium"->"中厂练习场景：侧重模块设计、协作边界、业务复杂度和稳定性。";
            case "large"->"大厂练习场景：侧重基础机制、算法推理、规模假设和系统权衡；基础档仍不要求大规模生产经历。";
            default->"通用练习场景：平衡基础、算法和项目实现。";
        };
        return Map.of("criteria",criteria,"focus",focus,"note","这是产品训练标准，不是企业录用标准；公司规模不代表实际题目难度。追问次数单独设置。");
    }
    /** Exact difficulty, technical relevance first; ties are shuffled on each retrieval. */
    public List<JsonNode> retrieve(JsonNode config,int limit) {
        String query=(config.path("role").asText()+" "+config.path("jd").asText()).toLowerCase(Locale.ROOT);
        Set<String> terms=new HashSet<>();
        for(JsonNode entry:entries.values())for(JsonNode tag:entry.path("tags")) {
            String term=tag.asText().toLowerCase(Locale.ROOT);
            if(!term.equals("通用")&&query.contains(term))terms.add(term);
        }
        List<JsonNode> candidates=new ArrayList<>(entries.values().stream().filter(e->
                e.path("difficulty").asText().equals(config.path("difficulty_resolved").asText(config.path("difficulty").asText()))
                        &&!unrelatedTrack(e,terms)
                        &&(terms.isEmpty()?hasTag(e,"通用"):hasTag(e,"通用")||terms.stream().anyMatch(t->hasTag(e,t)))).toList());
        Collections.shuffle(candidates);
        candidates.sort(Comparator.comparingInt((JsonNode e)->relevance(e,terms,config.path("company_style").asText())).reversed());
        return candidates.stream().limit(limit).<JsonNode>map(JsonNode::deepCopy).toList();
    }
    private static boolean hasTag(JsonNode entry,String tag) {for(JsonNode v:entry.path("tags"))if(v.asText().equalsIgnoreCase(tag))return true;return false;}
    private static boolean unrelatedTrack(JsonNode entry,Set<String> terms) {
        Set<String> tracks=new HashSet<>(List.of("java","go","python","前端","ai","c++"));
        Set<String> target=new HashSet<>(terms);target.retainAll(tracks);
        if(target.isEmpty())return false;
        boolean specialized=false;
        for(String track:tracks)if(hasTag(entry,track)){specialized=true;if(target.contains(track))return false;}
        return specialized;
    }
    private static int relevance(JsonNode entry,Set<String> terms,String style) {
        int score=0;for(String term:terms)if(hasTag(entry,term))score+=4;
        if(entry.path("source_kind").asText().equals("candidate_recollection"))score+=1;
        List<String> emphasis=switch(style){case "small"->List.of("业务","排障","项目");case "medium"->List.of("稳定性","设计","数据库");case "large"->List.of("原理","系统设计","网络","并发");default->List.of();};
        for(String tag:emphasis)if(hasTag(entry,tag))score++;
        return score;
    }
    public ObjectNode metadata() {
        ObjectNode result=mapper.createObjectNode().put("total",entries.size());
        Map<String,Integer> difficulties=new LinkedHashMap<>(),kinds=new LinkedHashMap<>();Set<String> companies=new TreeSet<>(),documents=new HashSet<>();
        Map<String,Map<String,String>> sources=new LinkedHashMap<>();Set<String> sourceDocuments=new HashSet<>();
        for(JsonNode e:entries.values()) {
            difficulties.merge(e.path("difficulty").asText(),1,Integer::sum);kinds.merge(e.path("source_kind").asText(),1,Integer::sum);
            if(!e.path("company").asText().isBlank())companies.add(e.path("company").asText());
            if(!e.path("source_url").asText().isBlank())documents.add(e.path("source_url").asText());
            if(!e.path("source_document").asText().isBlank())sourceDocuments.add(e.path("source_document").asText());
            String name=e.path("source_name").asText();sources.putIfAbsent(name,Map.of("name",name,"license",e.path("license").asText(),"revision",e.path("source_revision").asText()));
        }
        result.set("difficulties",mapper.valueToTree(difficulties));result.set("source_kinds",mapper.valueToTree(kinds));
        result.set("companies",mapper.valueToTree(companies));result.set("sources",mapper.valueToTree(sources.values()));result.put("documents",sourceDocuments.isEmpty()?documents.size():sourceDocuments.size());
        result.put("note","候选人回忆和公司题合集未经逐题核验，不代表企业官方规范；题目难度由本产品估计。公开版含原创示例，可导入本地面经包。");
        return result;
    }
}
