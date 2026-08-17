package com.offerforge.interview;

/**
 * 面试状态机阶段：OPENING → BASICS → PROJECT → DEEP → CLOSING → FINISHED。
 * DEEP_TRAINING 为训练模式深度训练子流程，不进入 next() 主链，退出时恢复 returnState。
 */
public enum InterviewState {

    OPENING("开场"),
    BASICS("基础考察"),
    PROJECT("项目考察"),
    DEEP("深度考察"),
    CLOSING("收尾"),
    FINISHED("已结束"),
    DEEP_TRAINING("深度训练");

    private final String label;

    InterviewState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public InterviewState next() {
        return switch (this) {
            case OPENING -> BASICS;
            case BASICS -> PROJECT;
            case PROJECT -> DEEP;
            case DEEP -> CLOSING;
            // DEEP_TRAINING 不进入主链：正常流程不会从它 next()，防御性返回终态
            case CLOSING, FINISHED, DEEP_TRAINING -> FINISHED;
        };
    }

    public boolean terminal() {
        return this == FINISHED;
    }

    /**
     * 是否会出题的考察阶段（开场与收尾不单独出题）。
     */
    public boolean questioning() {
        return this == BASICS || this == PROJECT || this == DEEP;
    }
}
