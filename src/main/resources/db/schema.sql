-- Easy Offer Forge 生产 DDL（MySQL 容器初始化 + 线上手动执行）

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
    UNIQUE KEY uk_training_record_session_id (session_id),
    KEY idx_training_record_user_time (user_id, finished_at)
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
