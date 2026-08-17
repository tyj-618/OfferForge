#!/usr/bin/env bash
# 生产迁移（任务 8 资料库归属隔离）：knowledge_item 增加 owner_user_id 列与索引，
# 旧唯一键 uk_knowledge_question(question) 替换为 (question, owner_user_id)
set -uo pipefail
PW=$(grep ^OFFERFORGE_DB_PASSWORD /opt/offerforge/.env | cut -d= -f2)
MYSQL="docker exec campuscircle-mysql mysql -uroot -p$PW offerforge_db"

echo "===== [1/4] add owner_user_id column ====="
$MYSQL -e "ALTER TABLE knowledge_item ADD COLUMN owner_user_id BIGINT NULL AFTER id;" 2>&1 | grep -v 'Using a password' || true

echo "===== [2/4] add idx_knowledge_owner ====="
$MYSQL -e "ALTER TABLE knowledge_item ADD KEY idx_knowledge_owner (owner_user_id);" 2>&1 | grep -v 'Using a password' || true

echo "===== [3/4] swap unique key ====="
$MYSQL -e "ALTER TABLE knowledge_item DROP INDEX uk_knowledge_question, ADD UNIQUE KEY uk_knowledge_question_owner (question, owner_user_id);" 2>&1 | grep -v 'Using a password' || true

echo "===== [4/4] verify ====="
$MYSQL -e "DESCRIBE knowledge_item; SHOW INDEX FROM knowledge_item; SELECT COUNT(*) AS total, COUNT(owner_user_id) AS owned FROM knowledge_item;" 2>/dev/null
echo "===== MIGRATE DONE ====="
