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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 面试题库：BASICS/DEEP 从知识库按分类取题，PROJECT 用内置项目类问题。
 * 从未问过的候选题中随机取一道，避免题目顺序固定；BASICS/DEEP 优先按当前难度筛选，
 * 该难度无未问题目时回退任意难度，避免题库未耗尽却误判枯竭。
 * 难度优先筛选逻辑不变，阶段题量分布由状态机保证。
 */
@Component
public class InterviewQuestionBank {

    private static final String PROJECT_RESOURCE = "interview/project-questions.json";
    private static final String PROJECT_KNOWLEDGE_POINT = "项目经历";
    private static final Set<String> BASICS_CATEGORIES = Set.of("Java基础", "Java集合", "计算机网络");
    private static final Set<String> DEEP_CATEGORIES = Set.of("Java并发", "JVM", "MySQL", "Redis", "Spring", "设计模式");

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
     */
    public Optional<InterviewQuestion> nextQuestion(InterviewState phase, Set<String> askedQuestions, Difficulty difficulty) {
        if (phase == InterviewState.PROJECT) {
            // 项目类问题不按难度筛选，知识点固定为项目经历
            List<String> candidates = projectEntries.stream()
                    .map(ProjectEntry::question)
                    .filter(question -> !askedQuestions.contains(question))
                    .toList();
            return candidates.isEmpty()
                    ? Optional.empty()
                    : Optional.of(new InterviewQuestion(pickRandom(candidates), null, PROJECT_KNOWLEDGE_POINT, null));
        }
        Set<String> categories = phase == InterviewState.BASICS ? BASICS_CATEGORIES : DEEP_CATEGORIES;
        List<KnowledgeItem> candidates = findByDifficulty(categories, difficulty).stream()
                .filter(item -> !askedQuestions.contains(item.getQuestion()))
                .toList();
        return candidates.isEmpty()
                ? Optional.empty()
                : Optional.of(toQuestion(pickRandom(candidates)));
    }

    private static <T> T pickRandom(List<T> candidates) {
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static InterviewQuestion toQuestion(KnowledgeItem item) {
        return new InterviewQuestion(item.getQuestion(), item.getAnswer(),
                item.getCategory(), item.getDifficulty());
    }

    /**
     * 优先按目标难度取题；该难度无未问题目时回退任意难度。
     */
    private List<KnowledgeItem> findByDifficulty(Set<String> categories, Difficulty difficulty) {
        if (difficulty != null) {
            List<KnowledgeItem> matched = knowledgeRepository.findByCategoryInAndDifficultyOrderById(categories, difficulty);
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return knowledgeRepository.findByCategoryInOrderById(categories);
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
