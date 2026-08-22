package com.offerforge.billing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计费折算：按「模型价目 × 加成率」向上取整到分（有用量最少 1 分）；
 * 价目解析：指定模型命中目录，未知模型回退首个免费档，目录为空不扣费。
 */
class BillingMeteringServiceTest {

    private final WalletService walletService = mock(WalletService.class);

    private BillingMeteringService service(BillingProperties properties) {
        return new BillingMeteringService(properties, walletService);
    }

    private BillingProperties.ModelConfig model(String id, long input, long output, boolean paidOnly) {
        BillingProperties.ModelConfig config = new BillingProperties.ModelConfig();
        config.setId(id);
        config.setName(id);
        config.setInputPerMillionCents(input);
        config.setOutputPerMillionCents(output);
        config.setPaidOnly(paidOnly);
        return config;
    }

    private BillingProperties properties(double markup, List<BillingProperties.ModelConfig> models) {
        BillingProperties properties = new BillingProperties();
        properties.setMarkup(markup);
        properties.setModels(models);
        return properties;
    }

    @Test
    void costCentsAppliesMarkupAndCeilsToCents() {
        BillingProperties.ModelConfig pricing = model("m", 15, 60, false);
        BillingMeteringService metering = service(properties(1.2, List.of(pricing)));

        // (1000*15 + 500*60)/1e6 = 0.045，×1.2 = 0.054 → 不足 1 分按 1 分保底
        assertThat(metering.costCents(pricing, 1000, 500, 1.2)).isEqualTo(1);
        // (1_000_000*15)/1e6 = 15，×1.2 = 18 分
        assertThat(metering.costCents(pricing, 1_000_000, 0, 1.2)).isEqualTo(18);
        // 小数向上取整：(1_000_000*15 + 1)/1e6 ≈ 15.0000001，×1.2 ≈ 18.00000012 → 19 分
        assertThat(metering.costCents(pricing, 1_000_000, 0, 1.2000000001)).isEqualTo(19);
        // 无任何用量不收费
        assertThat(metering.costCents(pricing, 0, 0, 1.2)).isZero();
    }

    @Test
    void recordUsageChargesSelectedModelPricing() {
        BillingProperties properties = properties(1.2,
                List.of(model("free", 100_000, 200_000, false), model("paid", 300_000, 600_000, true)));
        // 计量扣费 refNo 固定为 null：匹配器需用 isNull（anyString 不匹配 null）
        when(walletService.consume(eq(1L), anyLong(), isNull(), anyString())).thenAnswer(
                invocation -> invocation.getArgument(1));

        // paid 档：(100*300000 + 50*600000)/1e6 = 60，×1.2 = 72 分
        long deducted = service(properties).recordUsage(1L, "paid", 100, 50);

        assertThat(deducted).isEqualTo(72);
        verify(walletService).consume(1L, 72L, null, "model:paid");
    }

    @Test
    void recordUsageFallsBackToFirstFreeTierForUnknownModel() {
        BillingProperties properties = properties(1.0,
                List.of(model("paid", 999_999, 999_999, true), model("free", 100_000, 200_000, false)));
        when(walletService.consume(eq(1L), anyLong(), isNull(), anyString())).thenAnswer(
                invocation -> invocation.getArgument(1));

        // 未知模型回退首个免费档（付费档不作兜底）：(100*100000 + 50*200000)/1e6 = 20 分
        long deducted = service(properties).recordUsage(1L, "unknown-model", 100, 50);

        assertThat(deducted).isEqualTo(20);
        verify(walletService).consume(1L, 20L, null, "model:free");
    }

    @Test
    void recordUsageDoesNotChargeWhenCatalogEmpty() {
        long deducted = service(properties(1.2, List.of())).recordUsage(1L, null, 100, 50);

        assertThat(deducted).isZero();
        // never() 验证同样用 any() 兼容 null 参数（refNo 可能为 null）
        verify(walletService, never()).consume(anyLong(), anyLong(), any(), anyString());
    }
}
