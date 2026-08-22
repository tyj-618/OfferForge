package com.offerforge.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * token 计量扣费：计费模式下模型返回 usage 时，按「模型价目 × 加成率（默认 120%）」
 * 折算分币并从钱包扣减。价目缺失的模型回退目录首个免费档价目（宁可贵价兜底不提供免单）。
 */
@Service
public class BillingMeteringService {

    private static final Logger log = LoggerFactory.getLogger(BillingMeteringService.class);
    private static final double MILLION = 1_000_000.0;

    private final BillingProperties properties;
    private final WalletService walletService;

    public BillingMeteringService(BillingProperties properties, WalletService walletService) {
        this.properties = properties;
        this.walletService = walletService;
    }

    /**
     * 记录一次 LLM 调用的用量并扣费，返回实际扣减分币；价目目录为空时不扣费（配置异常保护）。
     * modelId 为空按系统默认模型（目录首个免费档）计价。
     */
    public long recordUsage(Long userId, String modelId, int inputTokens, int outputTokens) {
        BillingProperties.ModelConfig pricing = resolvePricing(modelId);
        if (pricing == null) {
            log.warn("billing pricing catalog empty, usage not charged userId={} model={}", userId, modelId);
            return 0;
        }
        long costCents = costCents(pricing, inputTokens, outputTokens, properties.getMarkup());
        if (costCents <= 0) {
            return 0;
        }
        return walletService.consume(userId, costCents, null, "model:" + pricing.getId());
    }

    /**
     * 计费折算：输入/输出分别按百万 token 单价计价，合计后乘加成率，向上取整到分；
     * 有任何用量时最少收 1 分，防止微量调用零成本滥用。
     */
    public long costCents(BillingProperties.ModelConfig pricing, int inputTokens, int outputTokens, double markup) {
        if (inputTokens <= 0 && outputTokens <= 0) {
            return 0;
        }
        double rawCost = (Math.max(0, inputTokens) * pricing.getInputPerMillionCents()
                + Math.max(0, outputTokens) * pricing.getOutputPerMillionCents()) / MILLION * markup;
        return Math.max(1, (long) Math.ceil(rawCost));
    }

    private BillingProperties.ModelConfig resolvePricing(String modelId) {
        BillingProperties.ModelConfig fallback = null;
        for (BillingProperties.ModelConfig model : properties.getModels()) {
            if (fallback == null && !model.isPaidOnly()) {
                fallback = model;
            }
            if (modelId != null && model.getId().equals(modelId)) {
                return model;
            }
        }
        if (modelId != null && fallback != null) {
            log.warn("billing pricing unknown for model={}, fallback to {}", modelId, fallback.getId());
        }
        return fallback;
    }
}
