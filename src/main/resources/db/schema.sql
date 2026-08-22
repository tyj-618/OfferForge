-- Easy Offer Forge 生产 DDL（MySQL 容器初始化 + 线上手动执行）

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(64)  NOT NULL,
    email       VARCHAR(128) NULL,
    status      TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（邮箱验证码登录：新增唯一邮箱列）：
-- ALTER TABLE users ADD COLUMN email VARCHAR(128) NULL AFTER nickname;
-- ALTER TABLE users ADD UNIQUE KEY uk_users_email (email);

CREATE TABLE IF NOT EXISTS knowledge_item (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT       NULL,
    question      VARCHAR(512) NOT NULL,
    answer        TEXT         NOT NULL,
    category      VARCHAR(64)  NOT NULL,
    difficulty    VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    tags          VARCHAR(512) NOT NULL DEFAULT '',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- 归属维度唯一：owner_user_id NULL=官方全局共享，非 NULL=用户私有；NULL 不参与唯一判定，
    -- 官方与不同用户的私有题互不冲突
    UNIQUE KEY uk_knowledge_question_owner (question, owner_user_id),
    KEY idx_knowledge_owner (owner_user_id),
    KEY idx_knowledge_category (category),
    KEY idx_knowledge_category_difficulty (category, difficulty)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（Phase 3 新增难度字段）：
-- ALTER TABLE knowledge_item ADD COLUMN difficulty VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' AFTER category;
-- ALTER TABLE knowledge_item ADD KEY idx_knowledge_category_difficulty (category, difficulty);

-- 存量库升级（任务 8 资料库归属隔离）：存量条目归属官方（owner_user_id 保持 NULL）
-- ALTER TABLE knowledge_item ADD COLUMN owner_user_id BIGINT NULL AFTER id;
-- ALTER TABLE knowledge_item DROP INDEX uk_knowledge_question;
-- ALTER TABLE knowledge_item ADD UNIQUE KEY uk_knowledge_question_owner (question, owner_user_id);
-- ALTER TABLE knowledge_item ADD KEY idx_knowledge_owner (owner_user_id);

CREATE TABLE IF NOT EXISTS interview_session (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    session_id    VARCHAR(64)  NOT NULL,
    position      VARCHAR(64)  NOT NULL,
    mode          VARCHAR(16)  NOT NULL DEFAULT 'practice',
    start_time    DATETIME(3)  NOT NULL,
    end_time      DATETIME(3)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    overall_score DOUBLE       NOT NULL,
    report_json   LONGTEXT     NOT NULL,
    UNIQUE KEY uk_interview_session_session_id (session_id),
    KEY idx_interview_session_user_time (user_id, start_time),
    KEY idx_interview_session_user_mode_time (user_id, mode, start_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（面试记录按训练/实战模式划分）：存量记录归为实战模式
-- ALTER TABLE interview_session ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'practice' AFTER position;
-- ALTER TABLE interview_session ADD KEY idx_interview_session_user_mode_time (user_id, mode, start_time);

-- 专项训练归档（任务 7）：训练完成后的简要成绩
CREATE TABLE IF NOT EXISTS training_record (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    category       VARCHAR(64)  NOT NULL,
    asked_count    INT          NOT NULL,
    average_score  DOUBLE       NOT NULL,
    max_difficulty VARCHAR(16)  NOT NULL,
    start_time     DATETIME(3)  NOT NULL,
    finished_at    DATETIME(3)  NOT NULL,
    details_json   LONGTEXT     NULL,
    UNIQUE KEY uk_training_record_session_id (session_id),
    KEY idx_training_record_user_time (user_id, finished_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（训练报告逐题明细）：旧记录 details_json 为 NULL，报告页降级为仅概要
-- ALTER TABLE training_record ADD COLUMN details_json LONGTEXT NULL AFTER finished_at;

-- 面试岗位设置：当前选中岗位 + 用户自定义岗位清单（岗位选择持久保留直到用户更改）
CREATE TABLE IF NOT EXISTS position_setting (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT      NOT NULL,
    current_position     VARCHAR(64) NULL,
    custom_positions_json LONGTEXT   NULL,
    UNIQUE KEY uk_position_setting_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS api_key (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    provider      VARCHAR(32)  NOT NULL,
    base_url      VARCHAR(255) NOT NULL,
    model         VARCHAR(64)  NOT NULL,
    encrypted_key VARCHAR(512) NOT NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（混合 API 模式新增 api_key 表）：
-- CREATE TABLE IF NOT EXISTS api_key ( ... 同上定义 ... );

-- 资料掌握度标记（绿勾/红叉）：同一题同一时刻只存一种标记，勾叉互相抵消，数量 1~10
CREATE TABLE IF NOT EXISTS knowledge_mastery (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id           BIGINT      NOT NULL,
    knowledge_item_id BIGINT      NOT NULL,
    mark_type         VARCHAR(8)  NOT NULL,
    mark_count        INT         NOT NULL,
    updated_at        DATETIME(3) NOT NULL,
    UNIQUE KEY uk_mastery_user_item (user_id, knowledge_item_id),
    KEY idx_mastery_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（掌握度标记新增 knowledge_mastery 表）：
-- CREATE TABLE IF NOT EXISTS knowledge_mastery ( ... 同上定义 ... );

-- 付费计费（充值余额 + token 计费）：钱包一用户一条，余额分币保底 0，行锁防并发超扣
CREATE TABLE IF NOT EXISTS user_wallet (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT   NOT NULL,
    balance_cents         BIGINT   NOT NULL DEFAULT 0,
    total_recharged_cents BIGINT   NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_wallet_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 钱包流水：每笔充值/消费/退款一条，记录变动后余额快照，账实可审计（amount_cents 恒正，方向由 type 表达）
CREATE TABLE IF NOT EXISTS wallet_transaction (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    amount_cents        BIGINT       NOT NULL,
    balance_after_cents BIGINT       NOT NULL,
    ref_no              VARCHAR(64)  NULL,
    detail              VARCHAR(128) NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_wallet_transaction_user_time (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 充值订单：创建即 PENDING，渠道确认后置 PAID 并入账；order_no 全局唯一（幂等键）
CREATE TABLE IF NOT EXISTS recharge_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(32) NOT NULL,
    user_id         BIGINT      NOT NULL,
    amount_cents    BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider        VARCHAR(16) NOT NULL,
    provider_txn_id VARCHAR(64) NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at         DATETIME    NULL,
    UNIQUE KEY uk_recharge_order_order_no (order_no),
    KEY idx_recharge_order_user_time (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（付费计费新增 3 张表）：
-- CREATE TABLE IF NOT EXISTS user_wallet ( ... 同上定义 ... );
-- CREATE TABLE IF NOT EXISTS wallet_transaction ( ... 同上定义 ... );
-- CREATE TABLE IF NOT EXISTS recharge_order ( ... 同上定义 ... );
