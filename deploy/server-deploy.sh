#!/usr/bin/env bash
# 服务器端：初始化 + 构建启动 + Nginx 配置（输出写入 deploy.log）
set -uo pipefail
cd /opt/offerforge

echo "===== [1/5] server-init ====="
bash /opt/offerforge/server-init.sh
echo "init exit=$?"

echo "===== [2/5] docker compose build ====="
docker compose -f docker-compose.prod.yml build
echo "build exit=$?"

echo "===== [3/5] docker compose up ====="
docker compose -f docker-compose.prod.yml up -d
echo "up exit=$?"

echo "===== [4/5] install nginx config ====="
sed '1,4d' /opt/offerforge/deploy/nginx-offerforge.conf > /tmp/offerforge-nginx.conf
sudo cp /tmp/offerforge-nginx.conf /etc/nginx/conf.d/offerforge.conf
sudo nginx -t && sudo nginx -s reload
echo "nginx exit=$?"

echo "===== [5/5] verify ====="
sleep 40
echo "--- backend health (localhost:8081) ---"
curl -s -m 10 http://localhost:8081/api/health
echo
echo "--- frontend (localhost:8082) ---"
curl -s -o /dev/null -w "frontend_http_code=%{http_code}\n" -m 10 http://localhost:8082/
echo "--- domain (Host header) ---"
curl -s -m 10 -H "Host: offerforge.joinuninook.com" http://localhost/api/health
echo
echo "===== DONE ====="
