package com.offerforge.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 难度枚举单元测试：升降档边界与容错解析。
 */
class DifficultyTests {

    @Test
    void raiseStepsUpAndCapsAtHard() {
        assertThat(Difficulty.EASY.raise()).isEqualTo(Difficulty.MEDIUM);
        assertThat(Difficulty.MEDIUM.raise()).isEqualTo(Difficulty.HARD);
        assertThat(Difficulty.HARD.raise()).isEqualTo(Difficulty.HARD);
    }

    @Test
    void lowerStepsDownAndFloorsAtEasy() {
        assertThat(Difficulty.HARD.lower()).isEqualTo(Difficulty.MEDIUM);
        assertThat(Difficulty.MEDIUM.lower()).isEqualTo(Difficulty.EASY);
        assertThat(Difficulty.EASY.lower()).isEqualTo(Difficulty.EASY);
    }

    @Test
    void labelsForFrontendDisplay() {
        assertThat(Difficulty.EASY.label()).isEqualTo("简单");
        assertThat(Difficulty.MEDIUM.label()).isEqualTo("中等");
        assertThat(Difficulty.HARD.label()).isEqualTo("困难");
    }

    @Test
    void parseToleratesMissingOrInvalidValue() {
        assertThat(Difficulty.parse("EASY")).isEqualTo(Difficulty.EASY);
        assertThat(Difficulty.parse(" hard ")).isEqualTo(Difficulty.HARD);
        assertThat(Difficulty.parse(null)).isEqualTo(Difficulty.MEDIUM);
        assertThat(Difficulty.parse("  ")).isEqualTo(Difficulty.MEDIUM);
        assertThat(Difficulty.parse("IMPOSSIBLE")).isEqualTo(Difficulty.MEDIUM);
    }
}
