package com.offerforge.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.EmbeddingClient;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.resume.ResumeResponse;
import com.offerforge.resume.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);
    private static final String BUILTIN_RESOURCE = "knowledge/java-backend-questions.json";
    private static final int FALLBACK_TOKEN_LIMIT = 12;
    private static final int FALLBACK_CANDIDATES_PER_TOKEN = 20;
    /** 用户上传未指定分组时的默认分组 */
    public static final String DEFAULT_CUSTOM_CATEGORY = "自定义";
    private static final int MAX_CATEGORY_LENGTH = 64;

    /** 官方分组有序列表：前端展示顺序与推荐打分遍历顺序 */
    private static final List<String> OFFICIAL_CATEGORIES = List.of(
            "Java基础", "Java集合", "Java并发", "JVM", "MySQL", "Redis", "计算机网络", "Spring", "设计模式", "Spring Boot", "算法");

    /** 简历关键词→官方分组映射：分组推荐按命中次数打分 */
    private static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.ofEntries(
            Map.entry("Java基础", List.of("java基础", "java 基础")),
            Map.entry("Java集合", List.of("集合", "collection", "hashmap", "arraylist")),
            Map.entry("Java并发", List.of("并发", "多线程", "线程池", "concurrent", "juc")),
            Map.entry("JVM", List.of("jvm", "虚拟机", "垃圾回收", "gc")),
            Map.entry("MySQL", List.of("mysql", "sql", "数据库", "索引")),
            Map.entry("Redis", List.of("redis", "缓存")),
            Map.entry("计算机网络", List.of("计算机网络", "http", "tcp", "网络协议")),
            Map.entry("Spring", List.of("spring", "ioc", "aop", "springmvc")),
            Map.entry("设计模式", List.of("设计模式", "单例", "工厂")),
            Map.entry("Spring Boot", List.of("spring boot", "springboot", "springcloud", "微服务")),
            Map.entry("算法", List.of("算法", "数据结构", "leetcode", "力扣", "动态规划", "手写编程")));

    private final KnowledgeRepository repository;
    private final EmbeddingClient embeddingClient;
    private final ObjectProvider<KnowledgeIndexClient> indexClientProvider;
    private final ObjectMapper objectMapper;
    private final KnowledgeUploadParser uploadParser;
    private final ResumeService resumeService;

    public KnowledgeService(KnowledgeRepository repository, EmbeddingClient embeddingClient,
                            ObjectProvider<KnowledgeIndexClient> indexClientProvider, ObjectMapper objectMapper,
                            KnowledgeUploadParser uploadParser, ResumeService resumeService) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.indexClientProvider = indexClientProvider;
        this.objectMapper = objectMapper;
        this.uploadParser = uploadParser;
        this.resumeService = resumeService;
    }

    public ImportSummary importBuiltinKnowledge() {
        List<BuiltinEntry> entries = loadBuiltinEntries();
        KnowledgeIndexClient indexClient = indexClientProvider.getIfAvailable();
        int inserted = 0;
        int skipped = 0;
        for (BuiltinEntry entry : entries) {
            // 官方条目归属恒为 NULL，按官方域去重
            if (repository.findByQuestionAndOwnerUserIdIsNull(entry.question()).isPresent()) {
                skipped++;
                continue;
            }
            KnowledgeItem item = new KnowledgeItem();
            item.setQuestion(entry.question());
            item.setAnswer(entry.answer());
            item.setCategory(entry.category());
            item.setDifficulty(Difficulty.parse(entry.difficulty()));
            item.setTags(entry.tags() == null ? "" : String.join(",", entry.tags()));
            try {
                repository.save(item);
            } catch (DataIntegrityViolationException exception) {
                // 并发导入时唯一约束冲突，视为已存在，保持幂等语义
                skipped++;
                continue;
            }
            inserted++;
            indexIfPossible(indexClient, item);
        }
        log.info("knowledge import finished total={} inserted={} skipped={} indexed={}",
                entries.size(), inserted, skipped, indexClient != null);
        return new ImportSummary(entries.size(), inserted, skipped);
    }

    /**
     * 归属隔离检索：仅命中官方条目 + 本人私有条目，私有内容绝不跨用户可见。
     */
    public List<RetrievedKnowledge> search(Long userId, String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        KnowledgeIndexClient indexClient = indexClientProvider.getIfAvailable();
        if (indexClient != null) {
            try {
                List<Long> ids = indexClient.searchByVector(embeddingClient.embed(query), limit);
                if (ids.isEmpty()) {
                    return List.of();
                }
                Map<Long, KnowledgeItem> byId = repository.findAllById(ids).stream()
                        .collect(Collectors.toMap(KnowledgeItem::getId, Function.identity()));
                List<RetrievedKnowledge> results = new ArrayList<>();
                for (int rank = 0; rank < ids.size(); rank++) {
                    KnowledgeItem item = byId.get(ids.get(rank));
                    // ES 索引无归属维度，取回后按可见性过滤（官方 + 本人私有）
                    if (item == null || !isVisibleTo(item, userId)) {
                        continue;
                    }
                    results.add(toRetrieved(item, 1.0 / (rank + 1)));
                }
                return results;
            } catch (Exception exception) {
                // ES/embedding 运行时故障不阻断问答，降级到关键词检索
                log.warn("vector search failed, fallback to keyword search: {}", exception.getMessage());
            }
        }
        return keywordFallback(userId, query, limit);
    }

    private static boolean isVisibleTo(KnowledgeItem item, Long userId) {
        return item.getOwnerUserId() == null || item.getOwnerUserId().equals(userId);
    }

    private List<RetrievedKnowledge> keywordFallback(Long userId, String query, int limit) {
        Set<String> tokens = new LinkedHashSet<>(com.offerforge.ai.MockEmbeddingClient.tokenize(query));
        Map<Long, KnowledgeItem> candidates = new HashMap<>();
        Map<Long, Double> scores = new HashMap<>();
        int processedTokens = 0;
        for (String token : tokens) {
            if (processedTokens++ >= FALLBACK_TOKEN_LIMIT) {
                break;
            }
            if (token.length() < 2) {
                continue;
            }
            for (KnowledgeItem item : repository.searchVisibleByKeyword(token, userId, PageRequest.of(0, FALLBACK_CANDIDATES_PER_TOKEN))) {
                candidates.putIfAbsent(item.getId(), item);
                double baseWeight = item.getQuestion().toLowerCase().contains(token) ? 3.0
                        : (item.getTags().toLowerCase().contains(token)
                                || item.getCategory().toLowerCase().contains(token)) ? 2.0
                        : 1.0;
                // 词长加权：长词（如 hashmap、雪崩）信息量高于短双字词，降低通用词噪声
                scores.merge(item.getId(), baseWeight * token.length(), Double::sum);
            }
        }
        return candidates.values().stream()
                .sorted(Comparator
                        .comparing((KnowledgeItem item) -> scores.getOrDefault(item.getId(), 0.0)).reversed()
                        .thenComparing(KnowledgeItem::getId))
                .limit(limit)
                .map(item -> toRetrieved(item, scores.getOrDefault(item.getId(), 0.0)))
                .toList();
    }

    private void indexIfPossible(KnowledgeIndexClient indexClient, KnowledgeItem item) {
        if (indexClient == null) {
            return;
        }
        try {
            List<Float> embedding = embeddingClient.embed(item.getQuestion() + " " + item.getTags());
            indexClient.upsert(item, embedding);
        } catch (Exception exception) {
            log.warn("knowledge indexing failed itemId={} reason={}", item.getId(), exception.getMessage());
        }
    }

    private List<BuiltinEntry> loadBuiltinEntries() {
        ClassPathResource resource = new ClassPathResource(BUILTIN_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() {
            });
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "内置知识库数据加载失败");
        }
    }

    /**
     * 用户上传资料入库：解析为问答对后按分组写入私域（owner=userId）；
     * 解析不出任何问答对抛 PARAM_ERROR；私域内题面重复跳过。
     */
    @Transactional
    public UploadSummary uploadKnowledge(Long userId, String filename, String content, String category) {
        String effectiveCategory = normalizeCategory(category);
        List<KnowledgeUploadParser.ParsedEntry> entries = uploadParser.parse(content);
        if (entries.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "文件「" + filename + "」未识别出有效问答对，请检查格式（支持 Q:/A: 标记或 Markdown 标题）");
        }
        int inserted = 0;
        int skipped = 0;
        for (KnowledgeUploadParser.ParsedEntry entry : entries) {
            if (repository.findByQuestionAndOwnerUserId(entry.question(), userId).isPresent()) {
                skipped++;
                continue;
            }
            KnowledgeItem item = new KnowledgeItem();
            item.setOwnerUserId(userId);
            item.setQuestion(entry.question());
            item.setAnswer(entry.answer());
            item.setCategory(effectiveCategory);
            item.setDifficulty(Difficulty.MEDIUM);
            item.setTags("");
            try {
                repository.save(item);
            } catch (DataIntegrityViolationException exception) {
                // 并发上传唯一约束冲突，视为已存在
                skipped++;
                continue;
            }
            inserted++;
        }
        log.info("knowledge upload finished userId={} file={} category={} parsed={} inserted={} skipped={}",
                userId, filename, effectiveCategory, entries.size(), inserted, skipped);
        return new UploadSummary(entries.size(), inserted, skipped);
    }

    /** 我的上传列表（仅本人私有条目） */
    public List<OwnedKnowledge> listMine(Long userId) {
        return repository.findByOwnerUserIdOrderById(userId).stream()
                .map(this::toOwnedKnowledge)
                .toList();
    }

    /** 官方题库列表（owner 恒为 NULL，全局共享只读） */
    public List<OwnedKnowledge> listOfficial() {
        return repository.findByOwnerUserIdIsNullOrderById().stream()
                .map(this::toOwnedKnowledge)
                .toList();
    }

    private OwnedKnowledge toOwnedKnowledge(KnowledgeItem item) {
        return new OwnedKnowledge(item.getId(), item.getQuestion(), item.getAnswer(),
                item.getCategory(), item.getDifficulty() == null ? null : item.getDifficulty().label());
    }

    /**
     * 批量删除本人私有条目；非本人 id 静默跳过（不泄露他人条目存在性），返回实际删除条数。
     */
    @Transactional
    public int batchDeleteOwned(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<KnowledgeItem> owned = repository.findByIdInAndOwnerUserId(ids, userId);
        repository.deleteAll(owned);
        log.info("knowledge batch deleted userId={} requested={} deleted={}", userId, ids.size(), owned.size());
        return owned.size();
    }

    /** 删除本人私有条目；不存在或非本人抛 NOT_FOUND（不泄露他人条目存在性） */
    @Transactional
    public void deleteOwned(Long userId, Long id) {
        KnowledgeItem item = repository.findByIdAndOwnerUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "资料不存在"));
        repository.delete(item);
        log.info("knowledge deleted userId={} itemId={}", userId, id);
    }

    /**
     * 可见分组视图：官方分组按固定顺序，自定义分组按首次上传顺序（排除与官方重名的分组）。
     */
    public CategoriesView visibleCategories(Long userId) {
        Set<String> officialSet = new LinkedHashSet<>(repository.findOfficialCategories());
        List<String> official = OFFICIAL_CATEGORIES.stream()
                .filter(officialSet::contains)
                .collect(Collectors.toCollection(ArrayList::new));
        // 官方顺序之外的官方分组（理论上不出现，兜底保完整）
        officialSet.stream().filter(category -> !official.contains(category)).forEach(official::add);
        List<String> custom = repository.findByOwnerUserIdOrderById(userId).stream()
                .map(KnowledgeItem::getCategory)
                .filter(category -> !officialSet.contains(category))
                .distinct()
                .toList();
        return new CategoriesView(official, custom);
    }

    /**
     * 分组推荐：简历技能/自我介绍/原文按关键词命中官方分组打分，命中多的排前；无简历或无命中返回空。
     */
    public List<String> recommendCategories(Long userId, Long resumeId) {
        ResumeResponse resume;
        try {
            resume = resumeId == null ? resumeService.latest(userId) : resumeService.getOwned(userId, resumeId);
        } catch (BusinessException exception) {
            return List.of();
        }
        String text = String.join("\n",
                        nullToEmpty(resume.skills()), nullToEmpty(resume.selfIntroduction()), nullToEmpty(resume.rawText()))
                .toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return List.of();
        }
        return OFFICIAL_CATEGORIES.stream()
                .map(category -> Map.entry(category, keywordHits(text, CATEGORY_KEYWORDS.get(category))))
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int keywordHits(String text, List<String> keywords) {
        if (keywords == null) {
            return 0;
        }
        int hits = 0;
        for (String keyword : keywords) {
            int index = 0;
            while ((index = text.indexOf(keyword, index)) >= 0) {
                hits++;
                index += keyword.length();
            }
        }
        return hits;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return DEFAULT_CUSTOM_CATEGORY;
        }
        String trimmed = category.trim();
        if (trimmed.length() > MAX_CATEGORY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "分组名称不能超过 " + MAX_CATEGORY_LENGTH + " 字");
        }
        return trimmed;
    }

    private RetrievedKnowledge toRetrieved(KnowledgeItem item, double score) {
        return new RetrievedKnowledge(item.getId(), item.getQuestion(), item.getAnswer(), item.getCategory(), score);
    }

    public record ImportSummary(int total, int inserted, int skipped) {
    }

    public record UploadSummary(int parsed, int inserted, int skipped) {
    }

    public record OwnedKnowledge(Long id, String question, String answer, String category, String difficulty) {
    }

    public record CategoriesView(List<String> official, List<String> custom) {
    }

    public record BuiltinEntry(String question, String answer, String category, List<String> tags, String difficulty) {
    }
}
