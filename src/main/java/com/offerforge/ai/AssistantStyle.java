package com.offerforge.ai;

/**
 * 助手语气风格：贯穿面试/专项训练的全部助手话术（出题、追问、导师点评、教练衔接）。
 * strict = 严肃专业（效率优先、不做寒暄与情绪安抚）；friendly = 和蔼可亲（温柔语气、高信息浓度，缺省）。
 * 旧会话反序列化缺失时一律按 friendly 处理。
 */
public final class AssistantStyle {

    public static final String STRICT = "strict";
    public static final String FRIENDLY = "friendly";

    private AssistantStyle() {
    }

    /** 归一化：null/非法值一律按 friendly，兼容旧会话与缺省请求 */
    public static String normalize(String style) {
        return STRICT.equals(style) ? STRICT : FRIENDLY;
    }

    public static boolean isStrict(String style) {
        return STRICT.equals(style);
    }
}
