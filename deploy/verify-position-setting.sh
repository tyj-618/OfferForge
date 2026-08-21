#!/usr/bin/env bash
# 生产验证：面试岗位设置持久化（position-setting GET/PUT）+ 前端产物哈希
set -uo pipefail
BASE="https://offerforge.joinuninook.com/api"
U="posverify_$(date +%s)"

echo "===== [1/6] register & login ====="
curl -s -X POST "$BASE/auth/register" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" > /dev/null
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"password\":\"123456\"}" | grep -o '"token":"[^"]*"' | head -1 | sed 's/"token":"//;s/"$//')
echo "TOKEN_LEN=${#TOKEN}"

echo "===== [2/6] initial GET (empty) ====="
curl -s "$BASE/interview/position-setting" -H "Authorization: Bearer $TOKEN"
echo

echo "===== [3/6] PUT preset + custom position ====="
curl -s -X PUT "$BASE/interview/position-setting" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"currentPosition":"Java 后端工程师","customPositions":[{"name":"大数据开发","tags":["Java基础","MySQL","自研中间件"]}]}'
echo

echo "===== [4/6] GET read back (persistent) ====="
curl -s "$BASE/interview/position-setting" -H "Authorization: Bearer $TOKEN"
echo

echo "===== [5/6] invalid payloads ====="
echo -n "unauthorized="; curl -s "$BASE/interview/position-setting" | grep -o '"code":[0-9]*'
echo -n "duplicate-name="; curl -s -X PUT "$BASE/interview/position-setting" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"customPositions":[{"name":"同岗","tags":[]},{"name":"同岗","tags":[]}]}' | grep -o '"code":[0-9]*'

echo "===== [6/6] frontend artifact ====="
sudo docker exec offerforge-frontend ls /usr/share/nginx/html/assets | grep InterviewView

echo "===== VERIFY DONE ====="
