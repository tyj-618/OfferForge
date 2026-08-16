package com.offerforge.ai;

/**
 * LLM 调用上下文（ThreadLocal）：在业务入口绑定用户凭据与 token 用量监听，
 * 使评估/追问/出题等间接调用链无需层层传递凭据；调用结束必须 clear 防止线程复用泄漏。
 */
public final class LlmCallContext {

    /** token 用量回调：模型返回 usage 时触发（用于面试会话累计） */
    public interface TokenUsageListener {
        void accept(int inputTokens, int outputTokens);
    }

    private static final ThreadLocal<LlmCredentials> CREDENTIALS = new ThreadLocal<>();
    private static final ThreadLocal<TokenUsageListener> USAGE_LISTENERS = new ThreadLocal<>();

    private LlmCallContext() {
    }

    public static void bind(LlmCredentials credentials) {
        CREDENTIALS.set(credentials);
    }

    /** 当前线程绑定的凭据；null 表示使用系统配置 */
    public static LlmCredentials current() {
        return CREDENTIALS.get();
    }

    public static void setUsageListener(TokenUsageListener listener) {
        USAGE_LISTENERS.set(listener);
    }

    /** 模型客户端解析出 usage 后调用；任一为空则忽略 */
    public static void recordUsage(Integer inputTokens, Integer outputTokens) {
        TokenUsageListener listener = USAGE_LISTENERS.get();
        if (listener != null && inputTokens != null && outputTokens != null) {
            listener.accept(inputTokens, outputTokens);
        }
    }

    public static void clear() {
        CREDENTIALS.remove();
        USAGE_LISTENERS.remove();
    }
}
