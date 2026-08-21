#!/usr/bin/env bash
# 验证「深入该模块」跳转修复：
# 1) 前端产物包含替换旧训练的二次确认弹窗文案；
# 2) 后端行为：旧训练未完成时深入跳转（fromInterview）会被训练互斥拒绝（根因复现），
#    结束旧训练后 fromInterview 开局成功（前端修复即按此顺序先 finish 再 start）。
set -u
ASSETS=/usr/share/nginx/html/assets
echo "===== 1. frontend artifacts ====="
sudo docker exec offerforge-frontend sh -c "grep -l '结束旧训练并开始' $ASSETS/*.js >/dev/null 2>&1 && echo OK replace-confirm-btn || echo MISS replace-confirm-btn"
sudo docker exec offerforge-frontend sh -c "grep -l '继续将结束并归档该训练后开始新训练' $ASSETS/*.js >/dev/null 2>&1 && echo OK replace-confirm-tip || echo MISS replace-confirm-tip"

echo "===== 2. backend api behavior ====="
BASE=http://localhost:8081
TS=$(date +%s)
USER=verifydd_$TS
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" >/dev/null
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token acquired: ${TOKEN:0:12}..."
curl -s -X POST $BASE/api/knowledge/import -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' >/dev/null
# 取官方题库第一个分组作为训练目标
CAT=$(curl -s "$BASE/api/knowledge/categories" -H "Authorization: Bearer $TOKEN" \
  | sed -n 's/.*"official":\["\([^"]*\)".*/\1/p')
echo "official category: $CAT"
# 开启旧训练（未完成，模拟用户遗留会话）
OLD=$(curl -s -X POST $BASE/api/training/start -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"category\":\"$CAT\"}" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
echo "old training session: $OLD"
# 开启模拟面试（训练模式），模拟深入跳转时的面试会话
ISID=$(curl -s -X POST $BASE/api/interview/start -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"mode":"training"}' | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
echo "interview session: $ISID"
# 复现根因：旧训练未完成时，深入跳转（fromInterview=true）仍被训练互斥拒绝
R1=$(curl -s -X POST $BASE/api/training/start -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"category\":\"$CAT\",\"fromInterview\":true}")
echo "start with stale training: $R1"
echo "$R1" | grep -q '已有一场专项训练正在进行' \
  && echo "OK root cause reproduced (conflict)" || echo "MISS conflict reproduction"
# 前端修复路径：先 finish 旧训练
curl -s -X POST $BASE/api/training/$OLD/finish -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' >/dev/null
# 再 fromInterview 开局 → 应成功（面试互斥豁免）
R2=$(curl -s -X POST $BASE/api/training/start -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d "{\"category\":\"$CAT\",\"fromInterview\":true}")
NEW=$(echo "$R2" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
if [ -n "$NEW" ]; then
  echo "OK new training started after finish: $NEW"
  curl -s -X POST $BASE/api/training/$NEW/finish -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' >/dev/null
else
  echo "MISS new training start failed: $R2"
fi
echo "===== VERIFY END ====="
