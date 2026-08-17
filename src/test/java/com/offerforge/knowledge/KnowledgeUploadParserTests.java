package com.offerforge.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上传资料解析单元测试：问答标记格式、Markdown 标题格式、无标记回退与边界。
 */
class KnowledgeUploadParserTests {

    private final KnowledgeUploadParser parser = new KnowledgeUploadParser();

    @Test
    void parsesMarkedQaPairsWithMixedMarks() {
        String content = """
                Q: HashMap 的底层原理？
                A: 数组 + 链表 + 红黑树。
                问：线程池参数如何设置？
                答：核心数、最大数、队列容量。
                """;

        List<KnowledgeUploadParser.ParsedEntry> entries = parser.parse(content);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).question()).isEqualTo("HashMap 的底层原理？");
        assertThat(entries.get(0).answer()).isEqualTo("数组 + 链表 + 红黑树。");
        assertThat(entries.get(1).question()).isEqualTo("线程池参数如何设置？");
    }

    @Test
    void unmarkedLinesAfterAnswerContinueTheAnswer() {
        String content = """
                Q: Redis 持久化方式？
                A: RDB 快照。
                AOF 追加日志。
                """;

        List<KnowledgeUploadParser.ParsedEntry> entries = parser.parse(content);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).answer()).contains("RDB 快照。").contains("AOF 追加日志。");
    }

    @Test
    void parsesMarkdownHeadingsAsQuestions() {
        String content = """
                # 第一题：JVM 内存结构？
                堆、栈、方法区。
                ## 第二题：GC 算法？
                标记清除、复制、标记整理。
                """;

        List<KnowledgeUploadParser.ParsedEntry> entries = parser.parse(content);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).question()).isEqualTo("第一题：JVM 内存结构？");
        assertThat(entries.get(0).answer()).isEqualTo("堆、栈、方法区。");
        assertThat(entries.get(1).question()).isEqualTo("第二题：GC 算法？");
    }

    @Test
    void blankOrUnrecognizedContentReturnsEmpty() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
        assertThat(parser.parse("这是一段没有任何标记的普通文本。")).isEmpty();
    }

    @Test
    void questionWithoutAnswerIsSkipped() {
        String content = """
                Q: 只有题面没有答案？
                """;

        assertThat(parser.parse(content)).isEmpty();
    }

    @Test
    void overlongQuestionIsSkipped() {
        String overlong = "长".repeat(513);
        String content = """
                Q: %s
                A: 答案
                Q: 正常题面？
                A: 正常答案
                """.formatted(overlong);

        List<KnowledgeUploadParser.ParsedEntry> entries = parser.parse(content);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).question()).isEqualTo("正常题面？");
    }

    @Test
    void windowsLineEndingsAreHandled() {
        String content = "Q: 问题一？\r\nA: 答案一\r\n";

        List<KnowledgeUploadParser.ParsedEntry> entries = parser.parse(content);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).answer()).isEqualTo("答案一");
    }
}
