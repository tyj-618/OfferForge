#!/usr/bin/env bash
# 服务器端：代码更新重建部署（跳过 init，保留现有 .env）
set -uo pipefail
cd /opt/offerforge

echo "===== [1/5] current .env AI config ====="
grep -E '^OFFERFORGE_(AI|SEARCH_EMBEDDING)_(PROVIDER|BASE_URL|MODEL)=' .env

echo "===== [2/5] backup .env and extract bundle ====="
cp .env /tmp/offerforge-env-backup
tar -xf full-bundle.tar
cp /tmp/offerforge-env-backup .env
chmod 600 .env
find deploy -name '*.sh' -exec sed -i 's/\r$//' {} +
sed -i 's/\r$//' server-init.sh
echo "--- .env AI provider after restore ---"
grep -E '^OFFERFORGE_AI_PROVIDER=|^OFFERFORGE_SEARCH_EMBEDDING_PROVIDER=' .env

echo "===== [3/5] docker compose build (sequential, low-memory VPS) ====="
# 小内存 VPS 上 backend(Maven) 与 frontend(npm) 并行构建曾导致整机资源耗尽失联，改串行
docker compose -f docker-compose.prod.yml build backend
echo "build backend exit=$?"
docker compose -f docker-compose.prod.yml build frontend
echo "build frontend exit=$?"

echo "===== [4/5] docker compose up -d ====="
docker compose -f docker-compose.prod.yml up -d
echo "up exit=$?"

echo "===== [5/5] verify ====="
sleep 45
echo "--- container status ---"
docker ps --filter name=offerforge --format '{{.Names}}\t{{.Status}}'
echo "--- backend health (localhost:8081) ---"
curl -s -m 10 http://localhost:8081/api/health
echo
echo "--- frontend (localhost:8082) ---"
curl -s -o /dev/null -w "frontend_http_code=%{http_code}\n" -m 10 http://localhost:8082/
echo "--- domain (Host header) ---"
curl -s -m 10 -H "Host: easyofferforge.com" http://localhost/api/health
echo
echo "--- backend last 30 log lines ---"
docker logs --tail 30 offerforge-backend 2>&1
echo "===== DONE ====="
