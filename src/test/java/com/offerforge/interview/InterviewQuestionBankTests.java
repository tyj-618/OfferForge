package com.offerforge.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.knowledge.Difficulty;
import com.offerforge.knowledge.KnowledgeItem;
import com.offerforge.knowledge.KnowledgeRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 题库选题单元测试：PROJECT 内置题随机选取且排除已问（Bug 1b）；
 * BASICS 科目连续性策略（连问/切换/耗尽回退）与难度筛选回退（任务 9）。
 */
class InterviewQuestionBankTests {

    private final InterviewQuestionBank bank = new InterviewQuestionBank(null, new ObjectMapper());

    @Test
    void projectQuestionsArePickedRandomlyExcludingAsked() {
        // 多轮选题应出现多道不同题目（随机性；内置题 8 道，50 轮全同题概率可忽略）
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 50; round++) {
            InterviewQuestionBank.InterviewQuestion question =
                    bank.nextQuestion(InterviewState.PROJECT, Set.of(), Difficulty.MEDIUM, null, 0, null,
                            1L, bank.categoriesFor(InterviewState.PROJECT)).orElseThrow();
            assertThat(question.knowledgePoint()).isEqualTo("项目经历");
            seen.add(question.question());
        }
        assertThat(seen.size()).isGreaterThan(1);
    }

    @Test
    void projectQuestionsExcludeAskedAndExhaustCleanly() {
        // 逐题问尽：每次返回的题不重复，全部问完后返回 empty
        Set<String> asked = new HashSet<>();
        List<String> all = new ArrayList<>();
        while (true) {
            Optional<InterviewQuestionBank.InterviewQuestion> next =
                    bank.nextQuestion(InterviewState.PROJECT, asked, null, null, 0, null,
                            1L, bank.categoriesFor(InterviewState.PROJECT));
            if (next.isEmpty()) {
                break;
            }
            assertThat(asked).doesNotContain(next.get().question());
            all.add(next.get().question());
            asked.add(next.get().question());
        }
        assertThat(all).doesNotHaveDuplicates();
        assertThat(all.size()).isGreaterThanOrEqualTo(3);

        // 候选集仅剩 1 题时必返回该题
        Set<String> allButLast = new HashSet<>(all.subList(0, all.size() - 1));
        assertThat(bank.nextQuestion(InterviewState.PROJECT, allButLast, null, null, 0, null,
                        1L, bank.categoriesFor(InterviewState.PROJECT)).orElseThrow().question())
                .isEqualTo(all.get(all.size() - 1));
    }

    /** 构造 BASICS 题库桩：同难度双科目候选，难度筛选与全量查询返回同一批题（可见性版本） */
    private InterviewQuestionBank bankWithBasicsItems(List<KnowledgeItem> items) {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        when(repository.findVisibleByCategoriesAndDifficulty(any(), eq(Difficulty.EASY), any())).thenReturn(items);
        when(repository.findVisibleByCategories(any(), any())).thenReturn(items);
        return new InterviewQuestionBank(repository, new ObjectMapper());
    }

    private static KnowledgeItem item(String question, String category) {
        KnowledgeItem knowledgeItem = new KnowledgeItem();
        knowledgeItem.setQuestion(question);
        knowledgeItem.setAnswer("参考答案：" + question);
        knowledgeItem.setCategory(category);
        knowledgeItem.setDifficulty(Difficulty.EASY);
        return knowledgeItem;
    }

    @Test
    void sameCategoryPreferredWhileUnderStreakLimit() {
        InterviewQuestionBank stubbed = bankWithBasicsItems(List.of(
                item("Java 基础题1", "Java基础"),
                item("Java 基础题2", "Java基础"),
                item("集合题1", "Java集合"),
                item("网络题1", "计算机网络")));

        // 上一题为 Java基础 且仅连问 1 题（<3）：多轮选题始终续问同科目
        for (int round = 0; round < 20; round++) {
            InterviewQuestionBank.InterviewQuestion next = stubbed
                    .nextQuestion(InterviewState.BASICS, Set.of(), Difficulty.EASY, "Java基础", 1, null,
                            1L, stubbed.categoriesFor(InterviewState.BASICS))
                    .orElseThrow();
            assertThat(next.knowledgePoint()).isEqualTo("Java基础");
        }
    }

    @Test
    void switchesToOtherCategoryAfterStreakLimit() {
        InterviewQuestionBank stubbed = bankWithBasicsItems(List.of(
                item("Java 基础题1", "Java基础"),
                item("集合题1", "Java集合"),
                item("网络题1", "计算机网络")));

        // Java基础 已连问 3 题达上限：多轮选题必切换到其他科目
        Set<String> seenCategories = new HashSet<>();
        for (int round = 0; round < 20; round++) {
            InterviewQuestionBank.InterviewQuestion next = stubbed
                    .nextQuestion(InterviewState.BASICS, Set.of(), Difficulty.EASY, "Java基础", 3, null,
                            1L, stubbed.categoriesFor(InterviewState.BASICS))
                    .orElseThrow();
            assertThat(next.knowledgePoint()).isNotEqualTo("Java基础");
            seenCategories.add(next.knowledgePoint());
        }
        // 切换科目时在剩余科目间随机（20 轮只出现单一科目概率极低）
        assertThat(seenCategories).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void fallsBackToOtherCategoryWhenSameCategoryExhausted() {
        InterviewQuestionBank stubbed = bankWithBasicsItems(List.of(
                item("Java 基础题1", "Java基础"),
                item("集合题1", "Java集合")));

        // 连问未达上限但同科目仅剩的题已问过 → 回退其他科目候选
        InterviewQuestionBank.InterviewQuestion next = stubbed
                .nextQuestion(InterviewState.BASICS, Set.of("Java 基础题1"), Difficulty.EASY, "Java基础", 1, null,
                        1L, stubbed.categoriesFor(InterviewState.BASICS))
                .orElseThrow();
        assertThat(next.question()).isEqualTo("集合题1");
    }

    @Test
    void fallsBackToSameCategoryWhenOtherCategoriesExhausted() {
        InterviewQuestionBank stubbed = bankWithBasicsItems(List.of(
                item("Java 基础题1", "Java基础"),
                item("Java 基础题2", "Java基础")));

        // 达连问上限但题库只剩同科目 → 回退全候选继续问，不误判枯竭
        InterviewQuestionBank.InterviewQuestion next = stubbed
                .nextQuestion(InterviewState.BASICS, Set.of(), Difficulty.EASY, "Java基础", 3, null,
                        1L, stubbed.categoriesFor(InterviewState.BASICS))
                .orElseThrow();
        assertThat(next.knowledgePoint()).isEqualTo("Java基础");
    }

    @Test
    void fallsBackToAnyDifficultyWhenTargetDifficultyEmpty() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        // 目标难度（HARD）无题 → 回退任意难度
        when(repository.findVisibleByCategoriesAndDifficulty(any(), eq(Difficulty.HARD), any())).thenReturn(List.of());
        when(repository.findVisibleByCategories(any(), any())).thenReturn(List.of(item("集合题1", "Java集合")));
        InterviewQuestionBank stubbed = new InterviewQuestionBank(repository, new ObjectMapper());

        InterviewQuestionBank.InterviewQuestion next = stubbed
                .nextQuestion(InterviewState.BASICS, Set.of(), Difficulty.HARD, null, 0, null,
                        1L, stubbed.categoriesFor(InterviewState.BASICS))
                .orElseThrow();
        assertThat(next.question()).isEqualTo("集合题1");
        assertThat(next.difficulty()).isEqualTo(Difficulty.EASY);
    }

    @Test
    void returnsEmptyWhenAllCandidatesAsked() {
        InterviewQuestionBank stubbed = bankWithBasicsItems(List.of(item("Java 基础题1", "Java基础")));

        assertThat(stubbed.nextQuestion(InterviewState.BASICS, Set.of("Java 基础题1"),
                Difficulty.EASY, "Java基础", 1, null,
                1L, stubbed.categoriesFor(InterviewState.BASICS))).isEmpty();
    }

    /**
     * 算法分组选题（任务 12）：候选池按生效分组过滤，仅当 categories 含“算法”时才能选中算法题；
     * DEEP 默认分组不含算法，需由服务层开启开关后掺入。
     */
    @Test
    void algorithmCategorySelectableOnlyWhenIncluded() {
        KnowledgeRepository repository = mock(KnowledgeRepository.class);
        KnowledgeItem algoItem = item("手写：两数之和", "算法");
        KnowledgeItem deepItem = item("线程池核心参数与拒绝策略？", "Java并发");
        // 模拟可见性查询按分组过滤：传入分组含哪个科目才返回哪科题目
        when(repository.findVisibleByCategoriesAndDifficulty(any(), any(), any())).thenAnswer(invocation -> {
            java.util.Collection<String> categories = invocation.getArgument(0);
            List<KnowledgeItem> pool = new ArrayList<>();
            if (categories.contains("Java并发")) {
                pool.add(deepItem);
            }
            if (categories.contains("算法")) {
                pool.add(algoItem);
            }
            return pool;
        });
        when(repository.findVisibleByCategories(any(), any())).thenReturn(List.of(deepItem, algoItem));
        InterviewQuestionBank stubbed = new InterviewQuestionBank(repository, new ObjectMapper());

        // 未掺入算法分组：多轮选题永远选不到算法题
        for (int round = 0; round < 10; round++) {
            InterviewQuestionBank.InterviewQuestion next = stubbed
                    .nextQuestion(InterviewState.DEEP, Set.of(), Difficulty.EASY, null, 0, null,
                            1L, stubbed.categoriesFor(InterviewState.DEEP))
                    .orElseThrow();
            assertThat(next.knowledgePoint()).isNotEqualTo("算法");
        }

        // 掺入算法分组后：多轮选题可选中算法题（两题各半，30 轮未出现概率可忽略）
        List<String> merged = new ArrayList<>(stubbed.categoriesFor(InterviewState.DEEP));
        merged.add("算法");
        Set<String> seenPoints = new HashSet<>();
        for (int round = 0; round < 30; round++) {
            InterviewQuestionBank.InterviewQuestion next = stubbed
                    .nextQuestion(InterviewState.DEEP, Set.of(), Difficulty.EASY, null, 0, null,
                            1L, merged)
                    .orElseThrow();
            seenPoints.add(next.knowledgePoint());
        }
        assertThat(seenPoints).contains("算法", "Java并发");
    }
}
