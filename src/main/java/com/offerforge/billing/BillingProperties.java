package com.offerforge.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 付费计费配置：总开关、支付渠道（审核通过前仅 mock）、成本加成率（默认 120%）、
 * 充值档位与模型价目目录。档位/价目调整只改配置不动代码。
 */
@ConfigurationProperties(prefix = "offerforge.billing")
public class BillingProperties {

    /** 总开关：生产环境支付渠道审核通过前先关闭（接口就绪但不开放入口） */
    private boolean enabled = false;
    /** 支付渠道：mock（模拟支付，联调用）/ wechat（微信支付，审核通过后启用） */
    private String provider = "mock";
    /** 成本加成率：按模型调用成本的该倍数向用户收费（1.2 = 120%） */
    private double markup = 1.2;
    /** 充值档位（金额单位：分） */
    private List<PackageConfig> packages = new ArrayList<>();
    /** 模型价目目录（价格单位：分/百万 token） */
    private List<ModelConfig> models = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public double getMarkup() {
        return markup;
    }

    public void setMarkup(double markup) {
        this.markup = markup;
    }

    public List<PackageConfig> getPackages() {
        return packages;
    }

    public void setPackages(List<PackageConfig> packages) {
        this.packages = packages;
    }

    public List<ModelConfig> getModels() {
        return models;
    }

    public void setModels(List<ModelConfig> models) {
        this.models = models;
    }

    /** 充值档位：如 ¥10 / ¥50 / ¥100 */
    public static class PackageConfig {
        private String id;
        private String name;
        private long amountCents;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getAmountCents() {
            return amountCents;
        }

        public void setAmountCents(long amountCents) {
            this.amountCents = amountCents;
        }
    }

    /** 模型价目条目：paidOnly=true 的模型需充值后才可选用；provider 指定模型所属官方端点（system=默认，deepseek=DeepSeek 官方） */
    public static class ModelConfig {
        private String id;
        private String name;
        private long inputPerMillionCents;
        private long outputPerMillionCents;
        private boolean paidOnly;
        private String provider = "system";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public long getInputPerMillionCents() {
            return inputPerMillionCents;
        }

        public void setInputPerMillionCents(long inputPerMillionCents) {
            this.inputPerMillionCents = inputPerMillionCents;
        }

        public long getOutputPerMillionCents() {
            return outputPerMillionCents;
        }

        public void setOutputPerMillionCents(long outputPerMillionCents) {
            this.outputPerMillionCents = outputPerMillionCents;
        }

        public boolean isPaidOnly() {
            return paidOnly;
        }

        public void setPaidOnly(boolean paidOnly) {
            this.paidOnly = paidOnly;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }
}
