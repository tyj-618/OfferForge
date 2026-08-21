#!/usr/bin/env bash
# 服务器端：存量库升级（面试岗位设置持久化）
# 新建 position_setting 表（当前选中岗位 + 用户自定义岗位清单 JSON）
# 幂等：CREATE TABLE IF NOT EXISTS；必须在新代码容器启动前执行
set -uo pipefail
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)

run_sql() {
  docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -N -e "$1" 2>/dev/null
}

echo "===== [1/2] position_setting table ====="
run_sql "CREATE TABLE IF NOT EXISTS position_setting (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id              BIGINT      NOT NULL,
    current_position     VARCHAR(64) NULL,
    custom_positions_json LONGTEXT   NULL,
    UNIQUE KEY uk_position_setting_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;"
echo "create table exit=$?"

echo "===== [2/2] verify ====="
run_sql "SHOW TABLES LIKE 'position_setting';"
run_sql "SHOW COLUMNS FROM position_setting;"
echo "===== MIGRATE DONE ====="
