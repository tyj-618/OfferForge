package com.offerforge.knowledge;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户上传资料解析：把 Markdown/TXT 文本切分为问答对。
 * 支持两种格式（自动识别）：
 * 1. 问答标记：Q:/问: 开头为题面，A:/答: 开头为参考答案（中英文冒号均可）；
 * 2. Markdown 标题：# / ## / ### 标题为题面，标题后正文为参考答案。
 * 无法识别出任何问答对时返回空列表，由调用方决定是否报错。
 */
@Component
public class KnowledgeUploadParser {

    /** 单个题面最大长度（与实体列长一致，超长条目跳过） */
    private static final int MAX_QUESTION_LENGTH = 512;

    private static final Pattern QUESTION_MARK = Pattern.compile("^(?:Q|问)\\s*[:：]\\s*(.+)$");
    private static final Pattern ANSWER_MARK = Pattern.compile("^(?:A|答)\\s*[:：]\\s*(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");

    public record ParsedEntry(String question, String answer) {
    }

    public List<ParsedEntry> parse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String[] lines = content.replace("\r\n", "\n").split("\n");
        List<ParsedEntry> marked = parseMarked(lines);
        if (!marked.isEmpty()) {
            return marked;
        }
        return parseHeadings(lines);
    }

    /** 问答标记格式：Q/问 与 A/答 交替出现 */
    private List<ParsedEntry> parseMarked(String[] lines) {
        List<ParsedEntry> entries = new ArrayList<>();
        String pendingQuestion = null;
        StringBuilder answer = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            var questionMatcher = QUESTION_MARK.matcher(line);
            var answerMatcher = ANSWER_MARK.matcher(line);
            if (questionMatcher.matches()) {
                flush(entries, pendingQuestion, answer);
                pendingQuestion = questionMatcher.group(1).strip();
                answer.setLength(0);
            } else if (answerMatcher.matches() && pendingQuestion != null) {
                answer.append(answerMatcher.group(1).strip());
            } else if (pendingQuestion != null && !line.isEmpty()) {
                // 未标记 A: 的后续行视为答案延续
                if (!answer.isEmpty()) {
                    answer.append('\n');
                }
                answer.append(line);
            }
        }
        flush(entries, pendingQuestion, answer);
        return entries;
    }

    /** Markdown 标题格式：标题为题面，标题之间的正文为答案 */
    private List<ParsedEntry> parseHeadings(String[] lines) {
        List<ParsedEntry> entries = new ArrayList<>();
        String pendingQuestion = null;
        StringBuilder body = new StringBuilder();
        for (String rawLine : lines) {
            String line = rawLine.strip();
            var matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                flush(entries, pendingQuestion, body);
                pendingQuestion = matcher.group(2).strip();
                body.setLength(0);
            } else if (pendingQuestion != null && !line.isEmpty()) {
                if (!body.isEmpty()) {
                    body.append('\n');
                }
                body.append(line);
            }
        }
        flush(entries, pendingQuestion, body);
        return entries;
    }

    private void flush(List<ParsedEntry> entries, String question, StringBuilder answer) {
        if (question != null && !question.isBlank() && !answer.isEmpty()
                && question.length() <= MAX_QUESTION_LENGTH) {
            entries.add(new ParsedEntry(question, answer.toString()));
        }
    }
}
