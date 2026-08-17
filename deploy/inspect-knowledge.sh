#!/usr/bin/env bash
# 检查线上 knowledge_item 表结构与索引（任务 8 迁移前置）
set -uo pipefail
PW=$(grep ^OFFERFORGE_DB_PASSWORD /opt/offerforge/.env | cut -d= -f2)
MYSQL="docker exec campuscircle-mysql mysql -uroot -p$PW offerforge_db"

echo "--- DESCRIBE ---"
$MYSQL -e "DESCRIBE knowledge_item;" 2>/dev/null
echo "--- INDEX ---"
$MYSQL -e "SHOW INDEX FROM knowledge_item;" 2>/dev/null
echo "--- row count ---"
$MYSQL -e "SELECT COUNT(*) AS total FROM knowledge_item;" 2>/dev/null
echo "===== INSPECT DONE ====="
