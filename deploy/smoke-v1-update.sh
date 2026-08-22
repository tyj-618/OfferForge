#!/usr/bin/env bash
# 服务器端：v1.0 更新冒烟（题库 318 / 反馈图文 / 管理台 / DeepSeek 价目 / 计费门控）
set -uo pipefail
BASE=http://localhost:8081

echo "===== [1] health ====="
curl -s -m 10 $BASE/api/health | head -c 300; echo

echo "===== [2] admin login ====="
R=$(curl -s -m 10 -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"123456"}')
TOKEN=$(echo "$R" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token_len=${#TOKEN}"

echo "===== [3] knowledge import (expect total=318 inserted=100) ====="
curl -s -m 60 -X POST $BASE/api/knowledge/import -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{}'; echo

echo "===== [4] feedback submit with image ====="
IMG="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
curl -s -m 10 -X POST $BASE/api/feedback -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"type\":\"BUG\",\"content\":\"v1.0 冒烟：图文反馈提交验证 $(date +%s)\",\"images\":[\"$IMG\"]}"; echo

echo "===== [5] feedback mine ====="
curl -s -m 10 $BASE/api/feedback/mine -H "Authorization: Bearer $TOKEN" | head -c 400; echo

echo "===== [6] admin feedbacks page ====="
curl -s -m 10 "$BASE/api/admin/feedbacks?page=1&size=5" -H "Authorization: Bearer $TOKEN" \
    | python3 -c 'import json,sys;d=json.load(sys.stdin)["data"];print("total=%d first_type=%s first_user=%s images=%d" % (d["total"], d["items"][0]["type"], d["items"][0]["username"], len(d["items"][0]["images"])))'

echo "===== [7] admin feedbacks no-token -> 40100 ====="
curl -s -m 10 "$BASE/api/admin/feedbacks" | head -c 200; echo

echo "===== [8] billing models contains deepseek-v4-flash ====="
curl -s -m 10 $BASE/api/billing/models -H "Authorization: Bearer $TOKEN" | python3 -c 'import json,sys;ms=json.load(sys.stdin)["data"];print([m["id"] for m in ms])'

echo "===== [9] billing status (expect enabled=false) ====="
curl -s -m 10 $BASE/api/billing/status -H "Authorization: Bearer $TOKEN"; echo

echo "===== [10] frontend keyword check ====="
IDX=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/index-*.js' | head -1)
for kw in '相关功能正在审核中' '隐私与安全' '问题反馈'; do
  n=$(docker exec offerforge-frontend grep -c "$kw" "$IDX" || true)
  echo "$kw: $n"
done
DOC=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/DocsView-*.js' | head -1)
echo "DocsView asset: $DOC"
docker exec offerforge-frontend grep -c 'deepseek-v4-flash' "$DOC" || true

echo "===== SMOKE DONE ====="
