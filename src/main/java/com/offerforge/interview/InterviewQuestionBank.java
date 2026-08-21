package com.offerforge.interview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.knowledge.Difficulty;
import com.offerforge.knowledge.KnowledgeItem;
import com.offerforge.knowledge.KnowledgeRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面试题库：BASICS/DEEP 从知识库按分类取题，PROJECT 用内置项目类问题。
 * 从未问过的候选题中随机取一道，避免题目顺序固定；BASICS/DEEP 优先按当前难度筛选，
 * 该难度无未问题目时回退任意难度，避免题库未耗尽却误判枯竭。
 * <p>由浅入深自适应选题（任务 9）：科目连续性策略——同 category 优先连问至多
 * {@link #MAX_SAME_CATEGORY_STREAK} 题，达上限后随机切换到其他科目；候选集内始终随机选题避免固定顺序。
 * 难度优先筛选逻辑不变，阶段题量分布由状态机保证。</p>
 */
@Component
public class InterviewQuestionBank {

    private static final String PROJECT_RESOURCE = "interview/project-questions.json";
    private static final String PROJECT_KNOWLEDGE_POINT = "项目经历";
    private static final Set<String> BASICS_CATEGORIES = Set.of("Java基础", "Java集合", "计算机网络");
    private static final Set<String> DEEP_CATEGORIES = Set.of("Java并发", "JVM", "MySQL", "Redis", "Spring", "设计模式");
    /** 同一科目最大连问题数：未达上限优先续问同科目，达上限后换科目 */
    static final int MAX_SAME_CATEGORY_STREAK = 3;

    private final KnowledgeRepository knowledgeRepository;
    private final List<ProjectEntry> projectEntries;

    public InterviewQuestionBank(KnowledgeRepository knowledgeRepository, ObjectMapper objectMapper) {
        this.knowledgeRepository = knowledgeRepository;
        this.projectEntries = loadProjectEntries(objectMapper);
    }

    public record InterviewQuestion(String question, String candidateAnswer,
                                    String knowledgePoint, Difficulty difficulty) {
    }

    /**
     * 取指定阶段下一道未问过的题（候选集内随机选取）；题库耗尽返回 empty。
     * 便利重载：不带掌握度权重（实战模式/历史调用，均匀随机）。
     */
    public Optional<InterviewQuestion> nextQuestion(InterviewState phase, Set<String> askedQuestions, Difficulty difficulty,
                                                    String lastCategory, int categoryStreak,
                                                    Map<String, Double> profileCategoryHints,
                                                    Long userId, Collection<String> categories) {
        return nextQuestion(phase, askedQuestions, difficulty, lastCategory, categoryStreak,
                profileCategoryHints, userId, categories, Map.of());
    }

    /**
     * 取指定阶段下一道未问过的题；题库耗尽返回 empty。
     *
     * @param lastCategory         上一题所属科目（可空）；非空时参与科目连续性策略
     * @param categoryStreak       该科目已连续问题数
     * @param profileCategoryHints 用户画像科目得分预留参数（任务 14 接入，当前版本不参与选题）
     * @param userId               当前用户：题库仅取官方 + 本人私有条目（任务 8 归属隔离）
     * @param categories           生效分组：用户勾选的分组非空时全量覆盖阶段默认分组
     * @param masteryWeights       掌握度选题权重（itemId → 倍数）：空 Map 为均匀随机（实战模式）；
     *                             权重 0（满 10 绿勾）的题直接剔除，红叉题按倍数增权
     */
    public Optional<InterviewQuestion> nextQuestion(InterviewState phase, Set<String> askedQuestions, Difficulty difficulty,
                                                    String lastCategory, int categoryStreak,
                                                    Map<String, Double> profileCategoryHints,
                                                    Long userId, Collection<String> categories,
                                                    Map<Long, Double> masteryWeights) {
        if (phase == InterviewState.PROJECT) {
            // 项目类问题不按难度筛选，知识点固定为项目经历（不属知识库，不参与掌握度加权）
            List<String> candidates = projectEntries.stream()
                    .map(ProjectEntry::question)
                    .filter(question -> !askedQuestions.contains(question))
                    .toList();
            return candidates.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new InterviewQuestion(pickRandom(candidates), null, PROJECT_KNOWLEDGE_POINT, null));
        }
        List<KnowledgeItem> candidates = findByDifficulty(categories, difficulty, userId).stream()
                .filter(item -> !askedQuestions.contains(item.getQuestion()))
                .filter(item -> weightOf(item, masteryWeights) > 0)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toQuestion(pickWithContinuity(candidates, lastCategory, categoryStreak, masteryWeights)));
    }

    /** 阶段默认分组：供服务层推导生效分组（用户勾选优先） */
    public Set<String> categoriesFor(InterviewState phase) {
        return phase == InterviewState.BASICS ? BASICS_CATEGORIES : DEEP_CATEGORIES;
    }

    /**
     * 科目连续性选题：连问未达上限时优先同科目（同科目候选耗尽则回退全候选）；
     * 达上限后随机切换到其他科目（无其他科目候选时回退全候选），避免固定顺序与单科目霸屏。
     * 候选集内按掌握度权重加权随机（空权重表退化为均匀随机）。
     */
    private static KnowledgeItem pickWithContinuity(List<KnowledgeItem> candidates, String lastCategory,
                                                    int categoryStreak, Map<Long, Double> masteryWeights) {
        if (lastCategory != null && categoryStreak > 0) {
            if (categoryStreak < MAX_SAME_CATEGORY_STREAK) {
                List<KnowledgeItem> sameCategory = candidates.stream()
                        .filter(item -> lastCategory.equals(item.getCategory()))
                        .toList();
                if (!sameCategory.isEmpty()) {
                    return pickWeighted(sameCategory, masteryWeights);
                }
            } else {
                List<KnowledgeItem> otherCategory = candidates.stream()
                        .filter(item -> !lastCategory.equals(item.getCategory()))
                        .toList();
                if (!otherCategory.isEmpty()) {
                    return pickWeighted(otherCategory, masteryWeights);
                }
            }
        }
        return pickWeighted(candidates, masteryWeights);
    }

    /** 掌握度加权随机：未标记题权重 1.0；红叉题按 1+x 增权，绿勾题按 (10-c)/10 降权 */
    private static KnowledgeItem pickWeighted(List<KnowledgeItem> candidates, Map<Long, Double> masteryWeights) {
        double total = 0;
        for (KnowledgeItem item : candidates) {
            total += weightOf(item, masteryWeights);
        }
        double roll = ThreadLocalRandom.current().nextDouble(total);
        double accumulated = 0;
        for (KnowledgeItem item : candidates) {
            accumulated += weightOf(item, masteryWeights);
            if (roll < accumulated) {
                return item;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private static double weightOf(KnowledgeItem item, Map<Long, Double> masteryWeights) {
        // id 可能为 null（未持久化对象），Map.of 等不可变 Map 对 null key 查询会抛 NPE
        if (item.getId() == null) {
            return 1.0;
        }
        Double weight = masteryWeights.get(item.getId());
        return weight == null ? 1.0 : weight;
    }

    private static <T> T pickRandom(List<T> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static InterviewQuestion toQuestion(KnowledgeItem item) {
        return new InterviewQuestion(item.getQuestion(), item.getAnswer(),
                item.getCategory(), item.getDifficulty());
    }

    /**
     * 优先按目标难度取题；该难度无未问题目时回退任意难度；仅取官方 + 本人私有条目。
     */
    private List<KnowledgeItem> findByDifficulty(Collection<String> categories, Difficulty difficulty, Long userId) {
        if (difficulty != null) {
            List<KnowledgeItem> matched = knowledgeRepository.findVisibleByCategoriesAndDifficulty(categories, difficulty, userId);
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return knowledgeRepository.findVisibleByCategories(categories, userId);
    }

    private List<ProjectEntry> loadProjectEntries(ObjectMapper objectMapper) {
        ClassPathResource resource = new ClassPathResource(PROJECT_RESOURCE);
        try (InputStream input = resource.getInputStream()) {
            return objectMapper.readValue(input, new TypeReference<>() {
            });
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "项目类面试题加载失败");
        }
    }

    record ProjectEntry(String question, String points) {
    }
}
