#!/usr/bin/env bash
# 掌握度标记升级：创建 knowledge_mastery 表（生产 ddl-auto=none，需手工建表）
set -uo pipefail
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)

docker exec -i campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db <<'SQL'
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
SQL
echo "create exit=$?"

echo "--- knowledge_mastery ---"
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -e "DESCRIBE knowledge_mastery;" 2>/dev/null
