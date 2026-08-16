#!/usr/bin/env bash
# 诊断 APIKEY_ENCRYPT_KEY 长度问题并强制修复为 hex 密钥
set -uo pipefail
cd /opt/offerforge

echo "--- 当前 .env 中密钥长度 ---"
FILE_KEY=$(sed -n 's/^APIKEY_ENCRYPT_KEY=//p' .env)
echo "file_len=${#FILE_KEY}"

echo "--- 容器内密钥长度 ---"
docker inspect offerforge-backend --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^APIKEY_ENCRYPT_KEY=//p' | awk '{print "container_len=" length($0)}'

echo "--- compose 解析后密钥长度 ---"
docker compose -f docker-compose.prod.yml config 2>/dev/null \
  | sed -n 's/^ *APIKEY_ENCRYPT_KEY: //p' | awk '{print "compose_len=" length($0)}'

# 强制生成新的 hex 密钥（32 字符，无特殊符号）
NEW_KEY=$(openssl rand -hex 16)
echo "new_key_len=${#NEW_KEY}"
sed -i "s/^APIKEY_ENCRYPT_KEY=.*/APIKEY_ENCRYPT_KEY=$NEW_KEY/" .env
echo "--- .env 已更新 ---"
sed -n 's/^APIKEY_ENCRYPT_KEY=//p' .env | awk '{print "after_len=" length($0)}'

# 强制重建后端容器
docker compose -f docker-compose.prod.yml up -d --force-recreate backend
sleep 45
echo "--- 容器状态 ---"
docker ps --format '{{.Names}} {{.Status}}' | grep offerforge
echo "--- health ---"
curl -s -m 10 http://localhost:8081/api/health
echo
echo "--- 后端最新日志 ---"
docker logs offerforge-backend --tail 5 2>&1 | cut -c1-150
