package com.offerforge.billing;

import com.offerforge.apikey.ApiKeyService;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import com.offerforge.exception.InsufficientBalanceException;
import com.offerforge.exception.QuotaExceededException;
import com.offerforge.quota.QuotaService;
import org.springframework.stereotype.Service;

/**
 * 开局准入判定（面试/专项训练共用）：自带 Key → 免费额度 → 充值余额计费 → 拒绝。
 * 付费模型（paidOnly）强制走计费模式且要求余额充足，避免免费额度白嫖高价模型。
 * 所有业务校验在额度扣减之前完成，非法请求不消耗任何用户资源。
 */
@Service
public class BillingAccessService {

    /** keySource：user=自带 Key / system=系统 Key；billable=true 时按 token 从余额扣费 */
    public record Decision(String keySource, boolean billable) {
    }

    private final ApiKeyService apiKeyService;
    private final QuotaService quotaService;
    private final WalletService walletService;
    private final BillingProperties properties;

    public BillingAccessService(ApiKeyService apiKeyService,
                                QuotaService quotaService,
                                WalletService walletService,
                                BillingProperties properties) {
        this.apiKeyService = apiKeyService;
        this.quotaService = quotaService;
        this.walletService = walletService;
        this.properties = properties;
    }

    public Decision decide(Long userId, String modelId) {
        String normalizedModel = modelId == null || modelId.isBlank() ? null : modelId.trim();
        BillingProperties.ModelConfig model = null;
        if (normalizedModel != null) {
            String modelIdFinal = normalizedModel;
            model = properties.getModels().stream()
                    .filter(candidate -> modelIdFinal.equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "不支持的模型，请从模型列表重新选择"));
        }
        // 自带 Key 优先：不占额度不计费，所选模型忽略（用户 Key 自带模型）
        if (apiKeyService.hasKey(userId)) {
            return new Decision("user", false);
        }
        if (model != null && model.isPaidOnly()) {
            // 付费模型：必须有余额（且付费开关开启），直接进计费模式不占免费额度
            if (!walletService.canBill(userId)) {
                throw new InsufficientBalanceException(walletService.balance(userId));
            }
            return new Decision("system", true);
        }
        if (!quotaService.isEnabled() || quotaService.consumeQuota(userId)) {
            return new Decision("system", false);
        }
        // 免费额度耗尽：有充值余额转计费模式，否则按既有契约拒绝并引导
        if (walletService.canBill(userId)) {
            return new Decision("system", true);
        }
        throw new QuotaExceededException(quotaService.checkQuota(userId));
    }
}
