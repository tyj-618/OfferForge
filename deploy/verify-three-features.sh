#!/usr/bin/env bash
# 三需求生产验证：短场免费退还 / 空作答报告低分措辞 / 简历背景注入 AI 面试官
set -u
BASE=https://easyofferforge.com/api

reg_token() {
  local u="vf_$1_$(date +%s)_$RANDOM"
  curl -sk -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"Passw0rd!123\"}" >/dev/null
  curl -sk -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$u\",\"password\":\"Passw0rd!123\"}" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["token"])'
}

sse_done() { grep -A1 '^event:done' | tail -1 | sed 's/^data://' ; }

extract_session() { python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["sessionId"])' ; }

extract_state() { python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["state"])' ; }

ask() { # $1=token $2=sessionId $3=message
  curl -sk -N -X POST "$BASE/interview/$2/ask" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "{\"message\":\"$3\"}"
}

echo "===== [1] health ====="
curl -sk "$BASE/health"
echo

echo "===== [2] short session quota refund ====="
T=$(reg_token a)
echo "--- quota before ---"
curl -sk "$BASE/quota" -H "Authorization: Bearer $T"
echo
SID=$(curl -sk -X POST "$BASE/interview/start" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{}' | extract_session)
echo "session=$SID"
echo "--- quota after start (expect remaining -1) ---"
curl -sk "$BASE/quota" -H "Authorization: Bearer $T"
echo
curl -sk -X POST "$BASE/interview/$SID/finish" -H "Authorization: Bearer $T" -H 'Content-Type: application/json' -d '{}' >/dev/null
echo "--- quota after finish (expect restored) ---"
curl -sk "$BASE/quota" -H "Authorization: Bearer $T"
echo
echo "--- backend refund log ---"
sudo docker logs offerforge-backend 2>&1 | grep 'short session quota refunded' | tail -2

echo "===== [3] complete session consumes quota ====="
T2=$(reg_token b)
curl -sk -X POST "$BASE/knowledge/import" -H "Authorization: Bearer $T2" -H 'Content-Type: application/json' -d '{}' >/dev/null
curl -sk -X POST "$BASE/interview/start" -H "Authorization: Bearer $T2" -H 'Content-Type: application/json' -d '{}' >/dev/null
echo "quota after start:"; curl -sk "$BASE/quota" -H "Authorization: Bearer $T2"; echo

echo "===== [4] resume background injection ====="
T3=$(reg_token c)
curl -sk -X POST "$BASE/knowledge/import" -H "Authorization: Bearer $T3" -H 'Content-Type: application/json' -d '{}' >/dev/null
RID=$(curl -sk -X POST "$BASE/resume" -H "Authorization: Bearer $T3" -H 'Content-Type: application/json' \
  -d '{"name":"测试候选人","skills":"Java, Spring Boot, Redis, MySQL","internships":"某互联网公司后端开发实习，参与营销活动接口开发，负责接口性能优化。","projects":[{"projectName":"校园二手商城","role":"后端负责人","techStack":"Spring Boot, Redis, MySQL","description":"实现商品、订单与缓存模块，支撑千人并发。"}]}' \
  | python3 -c 'import json,sys;d=json.load(sys.stdin)["data"];print(d.get("id") or d.get("resumeId") or "")')
echo "resumeId=$RID"
SID3=$(curl -sk -X POST "$BASE/interview/start" -H "Authorization: Bearer $T3" -H 'Content-Type: application/json' \
  -d "{\"resumeId\":$RID}" | extract_session)
echo "session3=$SID3"
for i in 1 2 3; do
  ask "$T3" "$SID3" "我是测试候选人，熟悉Java与Spring Boot技术栈，有后端实习经历，做过校园二手商城项目。" >/dev/null
  ST=$(curl -sk "$BASE/interview/$SID3/status" -H "Authorization: Bearer $T3" | extract_state)
  [ "$ST" != "OPENING" ] && break
done
echo "after intro state=$ST"
ask "$T3" "$SID3" "不会。" >/dev/null
echo "--- resume background flow done (summary content not logged, verified by source markers [6]) ---"
curl -sk "$BASE/interview/$SID3/status" -H "Authorization: Bearer $T3" | python3 -c 'import json,sys;print("state=",json.load(sys.stdin)["data"]["state"])'
curl -sk -X POST "$BASE/interview/$SID3/finish" -H "Authorization: Bearer $T3" -H 'Content-Type: application/json' -d '{}' >/dev/null

echo "===== [5] empty session report wording ====="
T4=$(reg_token d)
SID4=$(curl -sk -X POST "$BASE/interview/start" -H "Authorization: Bearer $T4" -H 'Content-Type: application/json' -d '{}' | extract_session)
curl -sk -X POST "$BASE/interview/$SID4/finish" -H "Authorization: Bearer $T4" -H 'Content-Type: application/json' -d '{}' >/dev/null
REPORT=$(curl -sk "$BASE/report/$SID4" -H "Authorization: Bearer $T4")
echo "overallScore=$(echo "$REPORT" | python3 -c 'import json,sys;print(json.load(sys.stdin)["data"]["overallScore"])')"
echo "strengths_count=$(echo "$REPORT" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["strengths"]))')"
echo "weakness_no_valid_answer=$(echo "$REPORT" | grep -c '没有产生有效作答')"
echo "suggestion_answer_three=$(echo "$REPORT" | grep -c '至少完整作答 3 道题')"

echo "===== [6] source markers ====="
grep -c 'refundIfShortSession' /opt/offerforge/src/main/java/com/offerforge/interview/InterviewService.java
grep -c 'MIN_BILLABLE_QUESTIONS = 5' /opt/offerforge/src/main/java/com/offerforge/interview/InterviewService.java
grep -c 'emptySessionSummary' /opt/offerforge/src/main/java/com/offerforge/report/ReportService.java
grep -c '针对性深挖' /opt/offerforge/src/main/java/com/offerforge/interview/InterviewPromptBuilder.java
grep -c '实习经历：' /opt/offerforge/src/main/java/com/offerforge/interview/InterviewService.java
grep -c '本次面试问答不足 5 题' /opt/offerforge/frontend/src/views/InterviewView.vue || true
echo "===== DONE ====="
