-- OfferForge 生产 DDL（MySQL 容器初始化 + 线上手动执行）

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    nickname    VARCHAR(64)  NOT NULL,
    status      TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_users_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_item (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    question    VARCHAR(512) NOT NULL,
    answer      TEXT         NOT NULL,
    category    VARCHAR(64)  NOT NULL,
    difficulty  VARCHAR(16)  NOT NULL DEFAULT 'MEDIUM',
    tags        VARCHAR(512) NOT NULL DEFAULT '',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_question (question),
    KEY idx_knowledge_category (category),
    KEY idx_knowledge_category_difficulty (category, difficulty)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 存量库升级（Phase 3 新增难度字段）：
-- ALTER TABLE knowledge_item ADD COLUMN difficulty VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' AFTER category;
-- ALTER TABLE knowledge_item ADD KEY idx_knowledge_category_difficulty (category, difficulty);
