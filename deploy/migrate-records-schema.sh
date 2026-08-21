#!/usr/bin/env bash
# 服务器端：存量库升级（历史记录划分与训练报告改造）
# 1. interview_session 增加 mode 列（存量归为 practice）+ 用户/模式/时间联合索引
# 2. training_record 增加 details_json 列（逐题明细 JSON）
# 幂等：已存在的列/索引自动跳过；必须在新代码容器启动前执行
set -uo pipefail
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)

run_sql() {
  docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -N -e "$1" 2>/dev/null
}

has_column() {
  run_sql "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='offerforge_db' AND TABLE_NAME='$1' AND COLUMN_NAME='$2';"
}

has_index() {
  run_sql "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='offerforge_db' AND TABLE_NAME='$1' AND INDEX_NAME='$2';"
}

echo "===== [1/4] interview_session.mode ====="
if [ "$(has_column interview_session mode)" = "0" ]; then
  run_sql "ALTER TABLE interview_session ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'practice' AFTER position;"
  echo "column added exit=$?"
else
  echo "column exists, skip"
fi

echo "===== [2/4] interview_session mode index ====="
if [ "$(has_index interview_session idx_interview_session_user_mode_time)" = "0" ]; then
  run_sql "ALTER TABLE interview_session ADD KEY idx_interview_session_user_mode_time (user_id, mode, start_time);"
  echo "index added exit=$?"
else
  echo "index exists, skip"
fi

echo "===== [3/4] training_record.details_json ====="
if [ "$(has_column training_record details_json)" = "0" ]; then
  run_sql "ALTER TABLE training_record ADD COLUMN details_json LONGTEXT NULL AFTER finished_at;"
  echo "column added exit=$?"
else
  echo "column exists, skip"
fi

echo "===== [4/4] verify ====="
run_sql "SHOW COLUMNS FROM interview_session LIKE 'mode';"
run_sql "SHOW COLUMNS FROM training_record LIKE 'details_json';"
echo "===== MIGRATE DONE ====="
