#!/usr/bin/env bash
# 验证历史记录改造：面试归档 mode + history 模式过滤 + 训练记录分页 + 训练报告明细
set -u
B=https://offerforge.joinuninook.com/api
U="hist_$(date +%s%N)"

curl -s -X POST "$B/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" > /dev/null
T=$(curl -s -X POST "$B/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "TOKEN_LEN=${#T}"

curl -s -X POST "$B/knowledge/import" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{}' > /dev/null

echo "--- [1] 训练模式面试 start -> finish 归档 ---"
S=$(curl -s -X POST "$B/interview/start" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"position":"Java 后端","mode":"training"}')
SID=$(echo "$S" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' | head -1)
echo "INTERVIEW_SESSION=$SID"
curl -s -X POST "$B/interview/$SID/finish" -H "Authorization: Bearer $T" | grep -o '"code":[0-9]*'

echo "--- [2] history 模式过滤 ---"
echo -n "mode=training total="; curl -s "$B/report/history?mode=training" -H "Authorization: Bearer $T" | grep -o '"totalElements":[0-9]*'
echo -n "mode=practice total="; curl -s "$B/report/history?mode=practice" -H "Authorization: Bearer $T" | grep -o '"totalElements":[0-9]*'
echo -n "content mode field="; curl -s "$B/report/history?mode=training" -H "Authorization: Bearer $T" | grep -o '"mode":"[a-z]*"' | head -1
echo -n "invalid mode code="; curl -s "$B/report/history?mode=xx" -H "Authorization: Bearer $T" | grep -o '"code":[0-9]*'

echo "--- [3] 专项训练走一题后结束 ---"
TS=$(curl -s -X POST "$B/training/start" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"category":"Java基础"}')
TSID=$(echo "$TS" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' | head -1)
echo "TRAINING_SESSION=$TSID"
curl -s -X POST "$B/training/$TSID/answer" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' \
  -d '{"message":"我从底层原理讲起，结合实际应用场景，先总结核心要点和常见误区，再补充性能优化与线上排查的实践经验。"}' > /dev/null
curl -s -X POST "$B/training/$TSID/finish" -H "Authorization: Bearer $T" | grep -o '"finished":[a-z]*'

echo "--- [4] 训练记录分页 + 报告明细 ---"
R=$(curl -s "$B/training/records?page=0&size=5" -H "Authorization: Bearer $T")
echo -n "records total="; echo "$R" | grep -o '"totalElements":[0-9]*'
RID=$(echo "$R" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
echo "RECORD_ID=$RID"
REP=$(curl -s "$B/training/records/$RID/report" -H "Authorization: Bearer $T")
echo -n "report code="; echo "$REP" | grep -o '"code":[0-9]*'
echo -n "report rating="; echo "$REP" | grep -o '"rating":"[^"]*"'
echo -n "details has answer="; echo "$REP" | grep -c '"answer"'
echo -n "details has comment="; echo "$REP" | grep -c '"comment"'
echo DONE
