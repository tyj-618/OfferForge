#!/usr/bin/env bash
# 掌握度标记系统（绿勾/红叉）生产验证：
# 面试 mastered/dontknow 行为、专项训练 mastered/dontknow 行为、
# 资料库返回 checks/crosses、knowledge_mastery 落库、前端产物关键词。
set -uo pipefail
API=http://localhost:8081
TS=$(date +%s)
UA="mastery_iv_$TS"
UB="mastery_tr_$TS"
PWD1="Test123456"
INTRO="我有三年 Java 后端开发经验，熟悉 Spring Boot、MySQL、Redis，主导过电商订单系统开发，负责核心模块设计与性能优化。"

register_login() {
  local u=$1
  curl -s -m 20 -X POST $API/api/auth/register -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"$PWD1\"}" >/dev/null
  curl -s -m 20 -X POST $API/api/auth/login -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"$PWD1\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["token"])'
}

echo "===== [1] health ====="
curl -s -m 10 $API/api/health; echo

echo "===== [2] interview: mastered/dontknow ====="
TA=$(register_login "$UA")
echo "token_a=${TA:0:12}..."
curl -s -m 20 -X POST $API/api/knowledge/import -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{}' >/dev/null
SID_A=$(curl -s -m 20 -X POST $API/api/interview/start -H "Authorization: Bearer $TA" \
  -H 'Content-Type: application/json' -d '{"mode":"training"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["sessionId"])')
echo "interview_session=$SID_A"

# OPENING 阶段标记应被拒绝（40900）
echo "--- mastered in OPENING (expect error 40900) ---"
curl -s -m 20 -N -X POST -H "Accept: text/event-stream" -H "Authorization: Bearer $TA" \
  $API/api/interview/$SID_A/mastered | grep -o 'event:error\|40900' | sort -u

# 自我介绍直到离开 OPENING（真实 LLM 可能追问补充介绍，最多 3 轮）
for i in 1 2 3; do
  curl -s -m 180 -N -X POST -H 'Content-Type: application/json' -H "Accept: text/event-stream" \
    -H "Authorization: Bearer $TA" -d "{\"message\":\"$INTRO\"}" \
    $API/api/interview/$SID_A/ask >/dev/null
  STATE=$(curl -s -m 20 $API/api/interview/$SID_A/status -H "Authorization: Bearer $TA" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["state"])')
  echo "after intro $i state=$STATE"
  [ "$STATE" != "OPENING" ] && break
done

# mastered：期望 done + score:null（不计分），资料库该题加绿勾
echo "--- mastered turn (expect done + score null) ---"
curl -s -m 180 -N -X POST -H "Accept: text/event-stream" -H "Authorization: Bearer $TA" \
  $API/api/interview/$SID_A/mastered \
  | grep -o 'event:done\|event:error\|"score":null\|"action":"[A-Z_]*"' | sort | uniq -c
echo "--- official list after mastered ---"
curl -s -m 20 $API/api/knowledge/official -H "Authorization: Bearer $TA" | python3 -c '
import sys, json
items = json.load(sys.stdin)["data"]
checked = [i for i in items if i.get("checks", 0) > 0]
print("items=%d checked=%d checksField=%s" % (len(items), len(checked), "checks" in (items[0] if items else {})))'

# dontknow：期望 done + score:0（强制 0 分），资料库该题加红叉
echo "--- dontknow turn (expect done + score 0) ---"
curl -s -m 240 -N -X POST -H "Accept: text/event-stream" -H "Authorization: Bearer $TA" \
  $API/api/interview/$SID_A/dontknow \
  | grep -o 'event:done\|event:error\|"score":0[,.0-9}]*' | sort | uniq -c
echo "--- official list after dontknow ---"
curl -s -m 20 $API/api/knowledge/official -H "Authorization: Bearer $TA" | python3 -c '
import sys, json
items = json.load(sys.stdin)["data"]
crossed = [i for i in items if i.get("crosses", 0) > 0]
print("items=%d crossed=%d crossesField=%s" % (len(items), len(crossed), "crosses" in (items[0] if items else {})))'

echo "===== [3] training: mastered/dontknow ====="
TB=$(register_login "$UB")
echo "token_b=${TB:0:12}..."
curl -s -m 20 -X POST $API/api/knowledge/import -H "Authorization: Bearer $TB" \
  -H 'Content-Type: application/json' -d '{}' >/dev/null
SID_B=$(curl -s -m 20 -X POST $API/api/training/start -H "Authorization: Bearer $TB" \
  -H 'Content-Type: application/json' -d '{"category":"Java并发"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["sessionId"])')
echo "training_session=$SID_B"

echo "--- training mastered turn (expect done + score null) ---"
curl -s -m 180 -N -X POST -H "Accept: text/event-stream" -H "Authorization: Bearer $TB" \
  $API/api/training/$SID_B/mastered \
  | grep -o 'event:done\|event:error\|"score":null\|"finished":false' | sort | uniq -c
echo "--- training dontknow turn (expect done + score 0) ---"
curl -s -m 240 -N -X POST -H "Accept: text/event-stream" -H "Authorization: Bearer $TB" \
  $API/api/training/$SID_B/dontknow \
  | grep -o 'event:done\|event:error\|"score":0[,.0-9}]*' | sort | uniq -c
echo "--- training official marks ---"
curl -s -m 20 $API/api/knowledge/official -H "Authorization: Bearer $TB" | python3 -c '
import sys, json
items = json.load(sys.stdin)["data"]
checked = [i for i in items if i.get("checks", 0) > 0]
crossed = [i for i in items if i.get("crosses", 0) > 0]
print("checked=%d crossed=%d" % (len(checked), len(crossed)))'

echo "===== [4] DB knowledge_mastery ====="
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -e \
  "SELECT mark_type, COUNT(*) cnt, SUM(mark_count) total FROM knowledge_mastery GROUP BY mark_type;" 2>/dev/null

echo "===== [5] frontend assets keywords ====="
docker exec offerforge-frontend sh -c "grep -l 'mastered' /usr/share/nginx/html/assets/*.js | head -3"
docker exec offerforge-frontend sh -c "grep -l 'dontknow' /usr/share/nginx/html/assets/*.js | head -3"

echo "===== [6] backend source new-code marker ====="
grep -c "markMastered" /opt/offerforge/src/main/java/com/offerforge/interview/InterviewService.java
grep -c "weeklyDecay" /opt/offerforge/src/main/java/com/offerforge/knowledge/KnowledgeMasteryService.java

echo "===== DONE ====="
