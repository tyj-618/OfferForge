#!/usr/bin/env bash
# 修复 api_key 表缺失问题：单独执行建表并列出全部表
set -uo pipefail
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)

# 提取 schema.sql 中 api_key 建表语句并执行
docker exec -i campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db <<'SQL'
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
SQL
echo "create exit=$?"

echo "--- all tables ---"
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -e "SHOW TABLES;" 2>/dev/null
