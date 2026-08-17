#!/usr/bin/env bash
# 迁移后验证：注册→登录→官方导入→分组列表→我的资料
set -uo pipefail
API=http://localhost:8081/api
USER=mig_check_$(date +%s)

curl -s -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" > /dev/null

TOKEN=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token_len=${#TOKEN}"

echo "--- POST /knowledge/import ---"
curl -s -X POST $API/knowledge/import -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
echo
echo "--- GET /knowledge/categories ---"
curl -s $API/knowledge/categories -H "Authorization: Bearer $TOKEN" | head -c 400
echo
echo "--- GET /knowledge/mine ---"
curl -s $API/knowledge/mine -H "Authorization: Bearer $TOKEN"
echo
echo "===== VERIFY DONE ====="
