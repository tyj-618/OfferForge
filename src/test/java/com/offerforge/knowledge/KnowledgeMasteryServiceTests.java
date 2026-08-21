package com.offerforge.knowledge;

import com.offerforge.auth.UserEntity;
import com.offerforge.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 掌握度标记服务单元测试：勾叉抵消规则、1~10 上下限、选题权重公式、
 * 资料库计数视图与每周衰减（绿勾 -1 / 其余题红叉 +1）。
 */
class KnowledgeMasteryServiceTests {

    private static final Long USER_ID = 1L;
    private static final Long ITEM_ID = 100L;

    private KnowledgeMasteryRepository masteryRepository;
    private KnowledgeRepository knowledgeRepository;
    private UserRepository userRepository;
    private KnowledgeMasteryService service;

    @BeforeEach
    void setUp() {
        masteryRepository = mock(KnowledgeMasteryRepository.class);
        knowledgeRepository = mock(KnowledgeRepository.class);
        userRepository = mock(UserRepository.class);
        service = new KnowledgeMasteryService(masteryRepository, knowledgeRepository, userRepository,
                mock(PlatformTransactionManager.class));
    }

    private KnowledgeMastery mastery(KnowledgeMastery.MarkType type, int count) {
        KnowledgeMastery mastery = new KnowledgeMastery();
        mastery.setUserId(USER_ID);
        mastery.setKnowledgeItemId(ITEM_ID);
        mastery.setMarkType(type);
        mastery.setMarkCount(count);
        return mastery;
    }

    @Test
    void recordCheckCreatesNewMarkOrIncrementsExisting() {
        // 无记录 → 新建绿勾 1
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.empty());
        service.recordCheck(USER_ID, ITEM_ID);
        ArgumentCaptor<KnowledgeMastery> captor = ArgumentCaptor.forClass(KnowledgeMastery.class);
        verify(masteryRepository).save(captor.capture());
        assertThat(captor.getValue().getMarkType()).isEqualTo(KnowledgeMastery.MarkType.CHECK);
        assertThat(captor.getValue().getMarkCount()).isEqualTo(1);

        // 已有 3 绿勾 → +1
        KnowledgeMastery existing = mastery(KnowledgeMastery.MarkType.CHECK, 3);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.of(existing));
        service.recordCheck(USER_ID, ITEM_ID);
        assertThat(existing.getMarkCount()).isEqualTo(4);
    }

    @Test
    void incomingMarkCancelsOppositeMarkAndDeletesAtZero() {
        // 已有 3 红叉时获绿勾 → 抵消为 2 红叉
        KnowledgeMastery crossed = mastery(KnowledgeMastery.MarkType.CROSS, 3);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.of(crossed));
        service.recordCheck(USER_ID, ITEM_ID);
        assertThat(crossed.getMarkType()).isEqualTo(KnowledgeMastery.MarkType.CROSS);
        assertThat(crossed.getMarkCount()).isEqualTo(2);
        verify(masteryRepository, never()).delete(any());

        // 仅剩 1 红叉时获绿勾 → 归零删除记录（回到无标记状态）
        KnowledgeMastery lastCross = mastery(KnowledgeMastery.MarkType.CROSS, 1);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.of(lastCross));
        service.recordCheck(USER_ID, ITEM_ID);
        verify(masteryRepository).delete(lastCross);

        // 对称：已有 2 绿勾时获红叉 → 抵消为 1 绿勾
        KnowledgeMastery checked = mastery(KnowledgeMastery.MarkType.CHECK, 2);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.of(checked));
        service.recordCross(USER_ID, ITEM_ID);
        assertThat(checked.getMarkType()).isEqualTo(KnowledgeMastery.MarkType.CHECK);
        assertThat(checked.getMarkCount()).isEqualTo(1);
    }

    @Test
    void markCountIsCappedAtTen() {
        // 满 10 绿勾再获绿勾：不再累加也不落库
        KnowledgeMastery fullCheck = mastery(KnowledgeMastery.MarkType.CHECK, 10);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.of(fullCheck));
        service.recordCheck(USER_ID, ITEM_ID);
        assertThat(fullCheck.getMarkCount()).isEqualTo(10);
        verify(masteryRepository, never()).save(any());

        // 满 10 红叉再获红叉：同样封顶
        KnowledgeMastery fullCross = mastery(KnowledgeMastery.MarkType.CROSS, 10);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID)).thenReturn(Optional.of(fullCross));
        service.recordCross(USER_ID, ITEM_ID);
        assertThat(fullCross.getMarkCount()).isEqualTo(10);
        verify(masteryRepository, never()).save(any());
    }

    @Test
    void weightsForAppliesCheckAndCrossFormula() {
        // 绿勾 c → (10-c)/10；红叉 x → 1+x；满 10 勾为 0（选题侧剔除）
        when(masteryRepository.findByUserId(2L)).thenReturn(List.of(masteryOf(2L, 201L, KnowledgeMastery.MarkType.CHECK, 3)));
        when(masteryRepository.findByUserId(3L)).thenReturn(List.of(masteryOf(3L, 202L, KnowledgeMastery.MarkType.CROSS, 5)));
        when(masteryRepository.findByUserId(4L)).thenReturn(List.of(masteryOf(4L, 203L, KnowledgeMastery.MarkType.CHECK, 10)));
        assertThat(service.weightsFor(2L)).containsEntry(201L, 0.7);
        assertThat(service.weightsFor(3L)).containsEntry(202L, 6.0);
        assertThat(service.weightsFor(4L)).containsEntry(203L, 0.0);
        // 无标记用户：空权重表（选题侧按 1.0 处理）
        when(masteryRepository.findByUserId(5L)).thenReturn(List.of());
        assertThat(service.weightsFor(5L)).isEmpty();
    }

    private KnowledgeMastery masteryOf(Long userId, Long itemId, KnowledgeMastery.MarkType type, int count) {
        KnowledgeMastery mastery = new KnowledgeMastery();
        mastery.setUserId(userId);
        mastery.setKnowledgeItemId(itemId);
        mastery.setMarkType(type);
        mastery.setMarkCount(count);
        return mastery;
    }

    @Test
    void summaryForExposesMutuallyExclusiveCounts() {
        when(masteryRepository.findByUserId(USER_ID)).thenReturn(List.of(
                masteryOf(USER_ID, 301L, KnowledgeMastery.MarkType.CHECK, 2),
                masteryOf(USER_ID, 302L, KnowledgeMastery.MarkType.CROSS, 4)));
        Map<Long, KnowledgeMasteryService.MasteryView> summary = service.summaryFor(USER_ID);
        assertThat(summary.get(301L)).isEqualTo(new KnowledgeMasteryService.MasteryView(2, 0));
        assertThat(summary.get(302L)).isEqualTo(new KnowledgeMasteryService.MasteryView(0, 4));
    }

    @Test
    void weeklyDecayReducesChecksRaisesCrossesAndMarksUnseenItems() {
        UserEntity user = new UserEntity();
        user.setId(USER_ID);
        when(userRepository.findAll()).thenReturn(List.of(user));

        KnowledgeMastery singleCheck = masteryOf(USER_ID, 401L, KnowledgeMastery.MarkType.CHECK, 1);
        KnowledgeMastery multiCheck = masteryOf(USER_ID, 402L, KnowledgeMastery.MarkType.CHECK, 3);
        KnowledgeMastery cross = masteryOf(USER_ID, 403L, KnowledgeMastery.MarkType.CROSS, 2);
        KnowledgeMastery fullCross = masteryOf(USER_ID, 404L, KnowledgeMastery.MarkType.CROSS, 10);
        when(masteryRepository.findByUserId(USER_ID)).thenReturn(List.of(singleCheck, multiCheck, cross, fullCross));
        // 404 已标记、405 无标记：无标记题新增 1 红叉
        when(knowledgeRepository.findVisibleItemIds(USER_ID)).thenReturn(List.of(404L, 405L));

        service.weeklyDecay();

        // 仅 1 个绿勾 → 归零删除
        verify(masteryRepository).delete(singleCheck);
        // 满 10 红叉不再累加
        assertThat(fullCross.getMarkCount()).isEqualTo(10);

        // 两次 saveAll：第一次为衰减后的既有记录（3 勾→2、2 叉→3；满 10 叉不参与），第二次为无标记题新增红叉
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeMastery>> captor = ArgumentCaptor.forClass(List.class);
        verify(masteryRepository, org.mockito.Mockito.times(2)).saveAll(captor.capture());
        List<List<KnowledgeMastery>> saved = captor.getAllValues();
        assertThat(saved.get(0)).containsExactlyInAnyOrder(multiCheck, cross);
        assertThat(multiCheck.getMarkCount()).isEqualTo(2);
        assertThat(cross.getMarkCount()).isEqualTo(3);
        assertThat(saved.get(1)).hasSize(1);
        KnowledgeMastery fresh = saved.get(1).get(0);
        assertThat(fresh.getKnowledgeItemId()).isEqualTo(405L);
        assertThat(fresh.getMarkType()).isEqualTo(KnowledgeMastery.MarkType.CROSS);
        assertThat(fresh.getMarkCount()).isEqualTo(1);
    }

    @Test
    void insertConflictRetriesOnceAndCancelsExistingMark() {
        // 并发首次写入撞唯一键（竞争者已先写入 1 红叉）：重试一次走已有记录分支做抵消
        KnowledgeMastery rivalCross = mastery(KnowledgeMastery.MarkType.CROSS, 1);
        when(masteryRepository.findByUserIdAndKnowledgeItemId(USER_ID, ITEM_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(rivalCross));
        doThrow(new DataIntegrityViolationException("uk_mastery_user_item"))
                .when(masteryRepository).save(any(KnowledgeMastery.class));

        service.recordCheck(USER_ID, ITEM_ID);

        // save 仅首次尝试调用过一次（抛冲突），重试走抵消分支归零删除，不再新增记录
        verify(masteryRepository, times(1)).save(any(KnowledgeMastery.class));
        verify(masteryRepository).delete(rivalCross);
    }

    @Test
    void weeklyDecayIsolatesFailurePerUser() {
        // 单用户衰减失败（如唯一键竞争）仅告警跳过，不影响其余用户
        UserEntity failing = new UserEntity();
        failing.setId(10L);
        UserEntity healthy = new UserEntity();
        healthy.setId(11L);
        when(userRepository.findAll()).thenReturn(List.of(failing, healthy));
        when(masteryRepository.findByUserId(10L)).thenThrow(new RuntimeException("simulated conflict"));
        KnowledgeMastery cross = masteryOf(11L, 501L, KnowledgeMastery.MarkType.CROSS, 1);
        when(masteryRepository.findByUserId(11L)).thenReturn(List.of(cross));
        when(knowledgeRepository.findVisibleItemIds(11L)).thenReturn(List.of(501L));

        service.weeklyDecay();

        // 失败用户未落库，健康用户照常衰减（红叉 +1）
        assertThat(cross.getMarkCount()).isEqualTo(2);
        verify(masteryRepository, never()).findByUserIdAndKnowledgeItemId(anyLong(), anyLong());
    }

    @Test
    void resolveItemIdPrefersPrivateOverOfficial() {
        KnowledgeItem privateItem = new KnowledgeItem();
        privateItem.setId(11L);
        KnowledgeItem officialItem = new KnowledgeItem();
        officialItem.setId(12L);
        when(knowledgeRepository.findByQuestionAndOwnerUserId("题目？", USER_ID)).thenReturn(Optional.of(privateItem));
        when(knowledgeRepository.findByQuestionAndOwnerUserIdIsNull("官方题？")).thenReturn(Optional.of(officialItem));

        assertThat(service.resolveItemId("题目？", USER_ID)).contains(11L);
        assertThat(service.resolveItemId("官方题？", USER_ID)).contains(12L);
        // 空白题面（题库外题目）直接返回 empty，不查库
        assertThat(service.resolveItemId("  ", USER_ID)).isEmpty();
        verify(knowledgeRepository, never()).findByQuestionAndOwnerUserId(eq("  "), anyLong());
    }
}
