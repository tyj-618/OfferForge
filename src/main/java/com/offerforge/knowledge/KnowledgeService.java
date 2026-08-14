package com.offerforge.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.ai.EmbeddingClient;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
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

    private final KnowledgeRepository repository;
    private final EmbeddingClient embeddingClient;
    private final ObjectProvider<KnowledgeIndexClient> indexClientProvider;
    private final ObjectMapper objectMapper;

    public KnowledgeService(KnowledgeRepository repository, EmbeddingClient embeddingClient,
                            ObjectProvider<KnowledgeIndexClient> indexClientProvider, ObjectMapper objectMapper) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.indexClientProvider = indexClientProvider;
        this.objectMapper = objectMapper;
    }

    public ImportSummary importBuiltinKnowledge() {
        List<BuiltinEntry> entries = loadBuiltinEntries();
        KnowledgeIndexClient indexClient = indexClientProvider.getIfAvailable();
        int inserted = 0;
        int skipped = 0;
        for (BuiltinEntry entry : entries) {
            if (repository.findByQuestion(entry.question()).isPresent()) {
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

    public List<RetrievedKnowledge> search(String query, int limit) {
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
                    if (item == null) {
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
        return keywordFallback(query, limit);
    }

    private List<RetrievedKnowledge> keywordFallback(String query, int limit) {
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
            for (KnowledgeItem item : repository.searchByKeyword(token, PageRequest.of(0, FALLBACK_CANDIDATES_PER_TOKEN))) {
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

    private RetrievedKnowledge toRetrieved(KnowledgeItem item, double score) {
        return new RetrievedKnowledge(item.getId(), item.getQuestion(), item.getAnswer(), item.getCategory(), score);
    }

    public record ImportSummary(int total, int inserted, int skipped) {
    }

    public record BuiltinEntry(String question, String answer, String category, List<String> tags, String difficulty) {
    }
}
