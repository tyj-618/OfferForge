#!/usr/bin/env bash
# 验证互斥与刷新恢复新字段：注册新用户 -> 开始训练模式面试 -> 检查 status 新字段 -> 面试进行中开训练被拒 -> 收尾
set -u
B=https://offerforge.joinuninook.com/api
U="mutex_$(date +%s%N)"

curl -s -X POST "$B/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" > /dev/null

T=$(curl -s -X POST "$B/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "TOKEN_LEN=${#T}"

S=$(curl -s -X POST "$B/interview/start" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"position":"Java 后端","mode":"training"}')
SID=$(echo "$S" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p' | head -1)
echo "SESSION=$SID"
echo "$S" | grep -o '"openingMessage":"[^"]\{0,30\}' | head -1

echo "--- status 新字段 ---"
curl -s "$B/interview/$SID/status" -H "Authorization: Bearer $T" \
  | grep -o '"openingMessage"\|"evaluating":[a-z]*\|"history":\[\]' | sort -u

echo "--- 面试进行中开训练（应 40900）---"
curl -s -X POST "$B/training/start" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' -d '{"category":"Java基础"}'
echo

echo "--- 收尾 ---"
curl -s -X POST "$B/interview/$SID/finish" -H "Authorization: Bearer $T" | grep -o '"code":[0-9]*'
echo DONE
