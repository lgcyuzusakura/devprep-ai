package com.devprep.ai.service;

import com.devprep.ai.domain.PracticeBank;
import com.devprep.ai.domain.TopicSummary;
import java.util.*;

/** Navigation groups only: question IDs, provenance and attempts remain in their original topics. */
public final class PracticeBanks {
    public record Definition(String id, String title, String description) { }
    public static final List<Definition> DEFINITIONS = List.of(
        new Definition("recommended", "推荐", "全部中文选择题，跨方向混合练习"),
        new Definition("java", "Java", "基础语法、集合、并发、JVM 与 Spring"),
        new Definition("network", "网络基础", "TCP/IP、HTTP、网络分层与 408 网络题"),
        new Definition("sql", "SQL", "SQL 语句、关系查询与数据库基础"),
        new Definition("database", "数据库", "MySQL、Redis、索引、事务与缓存"),
        new Definition("os", "操作系统", "进程、线程、内存、文件与 Linux"),
        new Definition("structures", "数据结构与算法", "数组、链表、树、图、排序与复杂度"),
        new Definition("architecture", "计算机组成", "指令、处理器、存储、总线与输入输出"),
        new Definition("frontend", "前端", "JavaScript、HTML、CSS、浏览器与框架"),
        new Definition("python", "Python", "语法、运行机制与常用开发知识"),
        new Definition("go", "Go", "语言基础、并发与工程实践"),
        new Definition("cpp", "C / C++", "语言基础、内存与对象模型"),
        new Definition("agent", "Agent", "工具调用、记忆、工作流与权限"),
        new Definition("prompt", "提示词工程", "提示设计、上下文、思维链与提示注入"),
        new Definition("ai", "AI 与大模型", "机器学习、Transformer、训练、推理与 RAG"),
        new Definition("backend", "后端与系统设计", "服务端、分布式、可靠性与架构设计"),
        new Definition("devops", "运维与云原生", "Linux、Docker、Kubernetes 与 SRE"),
        new Definition("testing", "测试开发", "测试方法、自动化与质量保障"),
        new Definition("cs408", "考研 408", "四门基础课程的综合题库"),
        new Definition("ruankao", "软考", "软件设计、架构与综合知识")
    );
    public static Definition require(String id) {
        return DEFINITIONS.stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow(() ->
                com.devprep.ai.api.ApiException.badRequest("invalid_bank", "找不到这个题库方向。"));
    }
    public static boolean matches(String bank, TopicSummary topic) {
        String d = topic.domain(), t = topic.title().toLowerCase(Locale.ROOT);
        return switch (bank) {
            case "recommended" -> true;
            case "java" -> Set.of("Java", "Java 基础", "并发与 JVM", "集合与数据结构", "Spring Boot").contains(d);
            case "network" -> d.equals("计算机网络") || t.contains("计算机网络") || (d.equals("软考") && t.contains("网络"));
            case "sql" -> (d.equals("数据库") || d.equals("软考")) && (t.contains("sql") || t.contains("数据库基础") || t.contains("关系数据库"));
            case "database" -> d.equals("数据库") || (d.equals("软考") && t.contains("数据库"));
            case "os" -> d.equals("操作系统") || t.contains("操作系统") || (d.contains("DevOps") && t.contains("linux"));
            case "structures" -> Set.of("算法", "集合与数据结构").contains(d) || t.contains("数据结构") || (d.equals("计算机基础") && t.contains("算法"));
            case "architecture" -> t.contains("计算机组成") || t.contains("计算机原理");
            case "frontend" -> d.equals("前端");
            case "python" -> d.equals("Python");
            case "go" -> d.equals("Go");
            case "cpp" -> d.equals("C++");
            case "agent" -> d.contains("Agent");
            case "prompt" -> t.contains("提示") || t.contains("prompt") || t.contains("思维链") || t.contains("上下文");
            case "ai" -> d.startsWith("AI") || d.contains("大模型");
            case "backend" -> Set.of("后端", "分布式", "系统设计", "Spring Boot").contains(d);
            case "devops" -> d.contains("DevOps") || d.contains("SRE");
            case "testing" -> d.equals("测试开发");
            case "cs408" -> d.equals("考研 408");
            case "ruankao" -> d.equals("软考");
            default -> false;
        };
    }
    public static List<PracticeBank> summarize(List<TopicSummary> topics) {
        return DEFINITIONS.stream().map(d -> {
            var group = topics.stream().filter(t -> matches(d.id(), t)).toList();
            int total = group.stream().mapToInt(TopicSummary::question_count).sum();
            int choices = group.stream().mapToInt(TopicSummary::choice_count).sum();
            int answered = group.stream().mapToInt(TopicSummary::answered_choice_count).sum();
            return new PracticeBank(d.id(), d.title(), d.description(), total, choices, answered, choices - answered,
                    total - choices, (int) group.stream().filter(t -> t.question_count() > 0).count());
        }).toList();
    }
    private PracticeBanks() { }
}
