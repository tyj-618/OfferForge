package com.offerforge.knowledge;

import com.offerforge.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 资料掌握度标记服务（绿勾/红叉）：
 * <ul>
 *   <li>抵消规则——同一题只存在一种标记：已有红叉时获绿勾则红叉 -1，反之亦然；</li>
 *   <li>数量上限 {@link #MAX_MARK_COUNT}：10 绿勾出题权重为 0（永不出题），10 红叉权重最高；</li>
 *   <li>每周衰减模拟遗忘：有绿勾则绿勾 -1，其余题目（含无标记）红叉 +1。</li>
 * </ul>
 */
@Service
public class KnowledgeMasteryService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMasteryService.class);

    /** 单题标记数量上限：绿勾满 10 永不出题，红叉满 10 出现频率最高 */
    public static final int MAX_MARK_COUNT = 10;

    private final KnowledgeMasteryRepository masteryRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final UserRepository userRepository;

    public KnowledgeMasteryService(KnowledgeMasteryRepository masteryRepository,
                                   KnowledgeRepository knowledgeRepository,
                                   UserRepository userRepository) {
        this.masteryRepository = masteryRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.userRepository = userRepository;
    }

    /** 资源库展示视图：某题的绿勾/红叉计数（二者互斥，至多一个非零） */
    public record MasteryView(int checks, int crosses) {
    }

    /**
     * 记录一次绿勾（已掌握/高分联动）：已有红叉则抵消一个红叉，否则绿勾 +1（上限 10）。
     */
    @Transactional
    public void recordCheck(Long userId, Long knowledgeItemId) {
        recordMark(userId, knowledgeItemId, KnowledgeMastery.MarkType.CHECK);
    }

    /**
     * 记录一次红叉（不知道/低分联动）：已有绿勾则抵消一个绿勾，否则红叉 +1（上限 10）。
     */
    @Transactional
    public void recordCross(Long userId, Long knowledgeItemId) {
        recordMark(userId, knowledgeItemId, KnowledgeMastery.MarkType.CROSS);
    }

    private void recordMark(Long userId, Long knowledgeItemId, KnowledgeMastery.MarkType incoming) {
        if (userId == null || knowledgeItemId == null) {
            return;
        }
        Optional<KnowledgeMastery> existing = masteryRepository.findByUserIdAndKnowledgeItemId(userId, knowledgeItemId);
        if (existing.isPresent()) {
            KnowledgeMastery mastery = existing.get();
            if (mastery.getMarkType() == incoming) {
                if (mastery.getMarkCount() >= MAX_MARK_COUNT) {
                    return;
                }
                mastery.setMarkCount(mastery.getMarkCount() + 1);
            } else {
                // 抵消：反向标记 -1，归零删除记录（回到无标记状态）
                mastery.setMarkCount(mastery.getMarkCount() - 1);
                if (mastery.getMarkCount() <= 0) {
                    masteryRepository.delete(mastery);
                    return;
                }
            }
            mastery.setUpdatedAt(Instant.now());
            return;
        }
        KnowledgeMastery mastery = new KnowledgeMastery();
        mastery.setUserId(userId);
        mastery.setKnowledgeItemId(knowledgeItemId);
        mastery.setMarkType(incoming);
        mastery.setMarkCount(1);
        mastery.setUpdatedAt(Instant.now());
        masteryRepository.save(mastery);
    }

    /**
     * 出题权重因子：itemId → 选题权重倍数（未标记题不在 Map 中，按 1.0 处理）。
     * 绿勾 c → (10-c)/10，满 10 勾为 0（选题侧剔除）；红叉 x → 1+x（满 10 叉为 11 倍）。
     */
    public Map<Long, Double> weightsFor(Long userId) {
        Map<Long, Double> weights = new HashMap<>();
        for (KnowledgeMastery mastery : masteryRepository.findByUserId(userId)) {
            if (mastery.getMarkType() == KnowledgeMastery.MarkType.CHECK) {
                weights.put(mastery.getKnowledgeItemId(),
                        (MAX_MARK_COUNT - mastery.getMarkCount()) / (double) MAX_MARK_COUNT);
            } else {
                weights.put(mastery.getKnowledgeItemId(), 1.0 + mastery.getMarkCount());
            }
        }
        return weights;
    }

    /** 资料库展示：itemId → 勾叉计数 */
    public Map<Long, MasteryView> summaryFor(Long userId) {
        Map<Long, MasteryView> summary = new HashMap<>();
        for (KnowledgeMastery mastery : masteryRepository.findByUserId(userId)) {
            boolean check = mastery.getMarkType() == KnowledgeMastery.MarkType.CHECK;
            summary.put(mastery.getKnowledgeItemId(), new MasteryView(
                    check ? mastery.getMarkCount() : 0,
                    check ? 0 : mastery.getMarkCount()));
        }
        return summary;
    }

    /**
     * 题面反查资料条目 id：优先本人私有，其次官方；题库外题目（项目类/生成题）返回 empty。
     */
    public Optional<Long> resolveItemId(String question, Long userId) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        return knowledgeRepository.findByQuestionAndOwnerUserId(question, userId)
                .or(() -> knowledgeRepository.findByQuestionAndOwnerUserIdIsNull(question))
                .map(KnowledgeItem::getId);
    }

    /**
     * 每周衰减（模拟遗忘）：绿勾 -1；其余题目（红叉与无标记）红叉 +1（上限 10）。
     * 周一 04:00（Asia/Shanghai）执行。
     */
    @Scheduled(cron = "0 0 4 * * MON", zone = "Asia/Shanghai")
    @Transactional
    public void weeklyDecay() {
        int decayed = 0;
        int newCrosses = 0;
        for (Long userId : userRepository.findAll().stream().map(user -> user.getId()).toList()) {
            Map<Long, KnowledgeMastery> byItem = new HashMap<>();
            List<KnowledgeMastery> toSave = new ArrayList<>();
            for (KnowledgeMastery mastery : masteryRepository.findByUserId(userId)) {
                byItem.put(mastery.getKnowledgeItemId(), mastery);
                if (mastery.getMarkType() == KnowledgeMastery.MarkType.CHECK) {
                    mastery.setMarkCount(mastery.getMarkCount() - 1);
                    mastery.setUpdatedAt(Instant.now());
                    if (mastery.getMarkCount() <= 0) {
                        masteryRepository.delete(mastery);
                    } else {
                        toSave.add(mastery);
                    }
                    decayed++;
                } else if (mastery.getMarkCount() < MAX_MARK_COUNT) {
                    mastery.setMarkCount(mastery.getMarkCount() + 1);
                    mastery.setUpdatedAt(Instant.now());
                    toSave.add(mastery);
                    decayed++;
                }
            }
            // 无标记的可见题：新增 1 个红叉（长期未练逐渐滑向需复习状态）
            Set<Long> marked = new HashSet<>(byItem.keySet());
            List<KnowledgeMastery> freshCrosses = new ArrayList<>();
            for (Long itemId : knowledgeRepository.findVisibleItemIds(userId)) {
                if (marked.contains(itemId)) {
                    continue;
                }
                KnowledgeMastery cross = new KnowledgeMastery();
                cross.setUserId(userId);
                cross.setKnowledgeItemId(itemId);
                cross.setMarkType(KnowledgeMastery.MarkType.CROSS);
                cross.setMarkCount(1);
                cross.setUpdatedAt(Instant.now());
                freshCrosses.add(cross);
            }
            masteryRepository.saveAll(toSave);
            masteryRepository.saveAll(freshCrosses);
            newCrosses += freshCrosses.size();
        }
        log.info("mastery weekly decay finished decayedMarks={} newCrosses={}", decayed, newCrosses);
    }
}
