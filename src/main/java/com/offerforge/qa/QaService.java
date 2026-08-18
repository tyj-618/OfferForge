package com.offerforge.qa;

import com.offerforge.ai.AiModelClient;
import com.offerforge.ai.AiStreamChunkConsumer;
import com.offerforge.ai.AiTextResult;
import com.offerforge.ai.ChatMessage;
import com.offerforge.knowledge.KnowledgeService;
import com.offerforge.knowledge.RetrievedKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);
    private static final int RETRIEVAL_LIMIT = 5;

    private final KnowledgeService knowledgeService;
    private final QaPromptBuilder promptBuilder;
    private final AiModelClient aiModelClient;

    public QaService(KnowledgeService knowledgeService, QaPromptBuilder promptBuilder, AiModelClient aiModelClient) {
        this.knowledgeService = knowledgeService;
        this.promptBuilder = promptBuilder;
        this.aiModelClient = aiModelClient;
    }

    public QaResponse ask(Long userId, String question) {
        long startedAt = System.currentTimeMillis();
        List<RetrievedKnowledge> retrieved = knowledgeService.search(userId, question, RETRIEVAL_LIMIT);
        List<ChatMessage> messages = promptBuilder.build(question, retrieved);
        AiTextResult result = aiModelClient.generateText(messages);
        List<Long> referencedIds = retrieved.stream().map(RetrievedKnowledge::itemId).toList();
        log.info("qa userId={} questionLength={} retrieved={} elapsedMs={}",
                userId, question.length(), retrieved.size(), System.currentTimeMillis() - startedAt);
        return new QaResponse(question, result.content(), referencedIds, result.requestId());
    }

    /**
     * 流式提问：检索知识后逐块流出回答，支持携带对话历史理解追问。
     * 鉴权与限流由 Controller/拦截器负责，本方法只处理业务主链路。
     */
    public QaStreamDone askStream(Long userId, String question, List<QaAskStreamRequest.HistoryEntry> history,
                                  AiStreamChunkConsumer chunkConsumer) throws IOException {
        long startedAt = System.currentTimeMillis();
        List<RetrievedKnowledge> retrieved = knowledgeService.search(userId, question, RETRIEVAL_LIMIT);
        List<ChatMessage> messages = promptBuilder.build(question, history, retrieved);
        aiModelClient.generateStream(messages, chunkConsumer);
        List<Long> referencedIds = retrieved.stream().map(RetrievedKnowledge::itemId).toList();
        log.info("qa stream userId={} questionLength={} history={} retrieved={} elapsedMs={}",
                userId, question.length(), history == null ? 0 : history.size(), retrieved.size(),
                System.currentTimeMillis() - startedAt);
        return new QaStreamDone(referencedIds);
    }
}
