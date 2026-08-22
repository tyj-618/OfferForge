#!/usr/bin/env bash
# 服务器端冒烟：短场（问答<5题）不消耗免费次数且不记录历史（面试+训练）
set -uo pipefail
API=http://localhost:8081/api

# 通用 JSON 路径提取（点分路径，数字段按数组下标；null/缺失输出空串；布尔小写）
jget() {
  python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in sys.argv[1].strip(".").split("."):
    if d is None: break
    d = d[int(k)] if isinstance(d,list) and k.isdigit() else (d.get(k) if isinstance(d,dict) else None)
if d is None: print("")
elif isinstance(d,bool): print(str(d).lower())
else: print(d)' "$1"
}

echo "===== [0/6] health ====="
curl -s -m 10 $API/health; echo
SUF=$(date +%s)
EMAIL="smkshort${SUF}@test.local"
USER="smkshort${SUF}"
echo "===== [1/6] register + login ====="
# 验证码存 Redis（email:code:{email}）：预置后直接注册，免真实发信；容器名动态获取避免拼写差异
RNAME=$(docker ps --format '{{.Names}}' | grep -i redis | head -1)
docker exec "$RNAME" redis-cli -n 1 set "email:code:$EMAIL" 135790 EX 300 > /dev/null
printf '{"email":"%s","code":"135790","username":"%s","password":"123456"}' "$EMAIL" "$USER" > /tmp/reg.json
curl -s -m 10 -X POST -H 'Content-Type: application/json' -d @/tmp/reg.json $API/auth/register; echo
TOKEN=$(curl -s -m 10 -X POST -H 'Content-Type: application/json' -d '{"username":"'"$USER"'","password":"123456"}' $API/auth/login | jget '.data.token')
echo "token_prefix=${TOKEN:0:12}"
AUTH="Authorization: Bearer $TOKEN"
echo "===== [2/6] short interview: archived=false + quota refund + history empty ====="
Q0=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_before=$Q0"
SID=$(curl -s -m 15 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/start | jget '.data.sessionId')
echo "sessionId=$SID"
Q1=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_after_start=$Q1 (expect Q0-1)"
FIN=$(curl -s -m 90 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/$SID/finish)
echo "finish archived=$(echo "$FIN" | jget '.data.archived') report=$(echo "$FIN" | jget '.data.report') (expect false/empty)"
Q2=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_after_finish=$Q2 (expect equal Q0=$Q0)"
echo "history_total=$(curl -s -m 10 -H "$AUTH" "$API/report/history?page=0&size=10" | jget '.data.totalElements') (expect 0)"
echo "report_query_code=$(curl -s -m 10 -H "$AUTH" $API/report/$SID | jget '.code') (expect 40400)"
echo "===== [3/6] short training: archived=false + records empty ====="
curl -s -m 20 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/knowledge/import; echo
CAT=$(curl -s -m 10 -H "$AUTH" $API/knowledge/categories | jget '.data.official.0')
echo "category=$CAT"
printf '{"category":"%s"}' "$CAT" > /tmp/tst.json
TSID=$(curl -s -m 30 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d @/tmp/tst.json $API/training/start | jget '.data.sessionId')
echo "training_sessionId=$TSID"
TFIN=$(curl -s -m 90 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/training/$TSID/finish)
echo "training finished=$(echo "$TFIN" | jget '.data.finished') archived=$(echo "$TFIN" | jget '.data.archived') (expect true/false)"
echo "training_records=$(curl -s -m 10 -H "$AUTH" "$API/training/records?page=0&size=10" | python3 -c 'import json,sys;print(len(json.load(sys.stdin)["data"]["content"]))') (expect 0)"
echo "===== [4/6] full interview (5+ questions): archived=true + consumes quota ====="
SID2=$(curl -s -m 15 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/start | jget '.data.sessionId')
echo "sessionId2=$SID2"
LONG='我对这个问题有比较深入的理解，从底层原理到工程实践都做过总结，包括核心流程、常见陷阱以及对应的优化方案。'
curl -s -m 60 -X POST -H "$AUTH" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"message":"我熟悉 Java 后端开发，做过电商项目。"}' $API/interview/$SID2/ask > /dev/null
for i in $(seq 1 12); do
  STATE=$(curl -s -m 10 -H "$AUTH" $API/interview/$SID2/status | jget '.data.state')
  echo "round=$i state=$STATE"
  if [ "$STATE" = "CLOSING" ] || [ "$STATE" = "FINISHED" ]; then break; fi
  curl -s -m 60 -X POST -H "$AUTH" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"message":"'"$LONG"'"}' $API/interview/$SID2/ask > /dev/null
done
curl -s -m 60 -X POST -H "$AUTH" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"message":"谢谢面试官，期待反馈。"}' $API/interview/$SID2/ask > /dev/null
FIN2=$(curl -s -m 120 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/$SID2/finish)
echo "finish2 archived=$(echo "$FIN2" | jget '.data.archived') score=$(echo "$FIN2" | jget '.data.report.overallScore') (expect true/number)"
Q3=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_final=$Q3 (expect Q0-1=$((Q0-1)))"
echo "history_total_final=$(curl -s -m 10 -H "$AUTH" "$API/report/history?page=0&size=10" | jget '.data.totalElements') (expect 1)"
echo "===== SMOKE DONE ====="
