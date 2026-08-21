#!/usr/bin/env bash
# 验证本轮五项修复是否上线：前端产物关键词 + 后端 API 行为
set -u
ASSETS=/usr/share/nginx/html/assets
echo "===== 1. containers & health ====="
sudo docker ps --format '{{.Names}} {{.Status}}' | grep offerforge
curl -s http://localhost:8081/actuator/health
echo

echo "===== 2. frontend artifacts ====="
sudo docker exec offerforge-frontend ls $ASSETS | grep -E 'InterviewView|HistoryView' || echo "MISS view chunks"
# 趋势双图并排（ASCII 类名）
sudo docker exec offerforge-frontend sh -c "grep -l 'trend-grid' $ASSETS/*.js $ASSETS/*.css 2>/dev/null" \
  && echo "OK trend-grid" || echo "MISS trend-grid"
# 「实战模式不提供跳过」（直接 grep 中文，兼容转义与明文两种产物形态）
sudo docker exec offerforge-frontend sh -c "grep -l '实战模式不提供跳过' $ASSETS/*.js 2>/dev/null || grep -l '\\\\u5b9e\\\\u6218\\\\u6a21\\\\u5f0f' $ASSETS/*.js 2>/dev/null" \
  && echo "OK skip-reject-tip" || echo "MISS skip-reject-tip"
# 「视为未能作答」
sudo docker exec offerforge-frontend sh -c "grep -l '视为未能作答' $ASSETS/*.js 2>/dev/null || grep -l '\\\\u89c6\\\\u4e3a\\\\u672a' $ASSETS/*.js 2>/dev/null" \
  && echo "OK skip-zero-tip" || echo "MISS skip-zero-tip"
# 「正在整理你的自我介绍」开场追问 progress 文案
sudo docker exec offerforge-frontend sh -c "grep -l '正在整理你的自我介绍' $ASSETS/*.js >/dev/null 2>&1 && echo OK intro-progress-tip || echo MISS intro-progress-tip"

echo "===== 3. backend api behavior ====="
BASE=http://localhost:8081
TS=$(date +%s)
USER=verify_$TS
# 注册 + 登录
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" >/dev/null
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token acquired: ${TOKEN:0:12}..."
# 导入知识库并开局（缺省实战模式）
curl -s -X POST $BASE/api/knowledge/import -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' >/dev/null
SID=$(curl -s -X POST $BASE/api/interview/start -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
echo "session: $SID"
# 开场作答：真实 LLM 可能触发自我介绍补充追问（新功能），循环作答直至离开 OPENING
for i in 1 2 3 4; do
  RESP=$(curl -s -X POST $BASE/api/interview/$SID/ask -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d '{"message":"我熟悉 Java 后端开发，做过电商项目，负责订单与库存接口开发，使用 Spring Boot 和 MySQL，日均请求量约十万级。"}')
  echo "$RESP" | grep -q '"state":"OPENING"' && echo "opening follow-up round $i" || break
done
# 实战模式跳过 → 应返回 40900 新文案
SKIP=$(curl -s -X POST $BASE/api/interview/$SID/skip -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}')
echo "practice-mode skip response: $SKIP"
echo "$SKIP" | grep -q '40900' && echo "$SKIP" | grep -q '实战模式不提供跳过' \
  && echo "OK skip rejected in practice with new message" || echo "MISS skip reject/new message"
# 结束实战会话，避免与训练会话冲突
curl -s -X POST $BASE/api/interview/$SID/finish -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' >/dev/null
# 训练模式开局并跳过 → 应视为未能作答计 0 分
SID2=$(curl -s -X POST $BASE/api/interview/start -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"mode":"training"}' | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
for i in 1 2 3 4; do
  RESP=$(curl -s -X POST $BASE/api/interview/$SID2/ask -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d '{"message":"我熟悉 Java 后端开发，做过电商项目，负责订单与库存接口开发，使用 Spring Boot 和 MySQL，日均请求量约十万级。"}')
  echo "$RESP" | grep -q '"state":"OPENING"' || break
done
SKIP2=$(curl -s -X POST $BASE/api/interview/$SID2/skip -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}')
echo "$SKIP2" | grep -q '未能作答' && echo "OK training skip scored zero" || echo "MISS training skip message: $SKIP2"
# progress 返回 mode 字段
curl -s "$BASE/api/report/progress?limit=10" -H "Authorization: Bearer $TOKEN" | grep -q '"mode"' \
  && echo "OK progress has mode" || echo "MISS progress mode"
echo "===== VERIFY END ====="
