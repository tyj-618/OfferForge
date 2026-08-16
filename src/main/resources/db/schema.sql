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

CREATE TABLE IF NOT EXISTS interview_session (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    session_id    VARCHAR(64)  NOT NULL,
    position      VARCHAR(64)  NOT NULL,
    start_time    DATETIME(3)  NOT NULL,
    end_time      DATETIME(3)  NOT NULL,
    status        VARCHAR(32)  NOT NULL,
    overall_score DOUBLE       NOT NULL,
    report_json   LONGTEXT     NOT NULL,
    UNIQUE KEY uk_interview_session_session_id (session_id),
    KEY idx_interview_session_user_time (user_id, start_time)
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
