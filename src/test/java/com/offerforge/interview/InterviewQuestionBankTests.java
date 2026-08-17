package com.offerforge.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.knowledge.Difficulty;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题库选题单元测试（Bug 1b）：PROJECT 内置题随机选取且排除已问题目。
 * BASICS/DEEP 依赖知识库仓储，由集成测试覆盖。
 */
class InterviewQuestionBankTests {

    private final InterviewQuestionBank bank = new InterviewQuestionBank(null, new ObjectMapper());

    @Test
    void projectQuestionsArePickedRandomlyExcludingAsked() {
        // 多轮选题应出现多道不同题目（随机性；内置题 8 道，50 轮全同题概率可忽略）
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 50; round++) {
            InterviewQuestionBank.InterviewQuestion question =
                    bank.nextQuestion(InterviewState.PROJECT, Set.of(), Difficulty.MEDIUM).orElseThrow();
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
                    bank.nextQuestion(InterviewState.PROJECT, asked, null);
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
        assertThat(bank.nextQuestion(InterviewState.PROJECT, allButLast, null).orElseThrow().question())
                .isEqualTo(all.get(all.size() - 1));
    }
}
