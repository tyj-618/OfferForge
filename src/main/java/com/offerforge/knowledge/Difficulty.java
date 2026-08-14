package com.offerforge.knowledge;

/**
 * 面试题难度分级：EASY → MEDIUM → HARD。
 * 面试过程中根据连续答题表现动态调整，出题时按当前难度筛选题库。
 */
public enum Difficulty {

    EASY("简单"),
    MEDIUM("中等"),
    HARD("困难");

    private final String label;

    Difficulty(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Difficulty raise() {
        return this == HARD ? HARD : values()[ordinal() + 1];
    }

    public Difficulty lower() {
        return this == EASY ? EASY : values()[ordinal() - 1];
    }

    /**
     * 容错解析：JSON 标注缺失或非法值时回退 MEDIUM。
     */
    public static Difficulty parse(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return MEDIUM;
        }
    }
}
