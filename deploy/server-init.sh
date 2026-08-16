#!/usr/bin/env bash
# OfferForge 服务器端初始化：生成 .env、建库建表
set -euo pipefail
cd /opt/offerforge

# 复用现有 MySQL root 密码
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)
# 生成 32 字符随机加密密钥（hex 格式避免 base64 的 = 填充被 .env 解析剥离）
if [ -f .env ] && grep -q '^APIKEY_ENCRYPT_KEY=' .env; then
  ENC_KEY=$(grep '^APIKEY_ENCRYPT_KEY=' .env | cut -d= -f2-)
fi
if [ ${#ENC_KEY} -ne 32 ]; then
  ENC_KEY=$(openssl rand -hex 16)
fi
echo "ENC_KEY length: ${#ENC_KEY}"

cat > .env <<EOF
MIDDLEWARE_NETWORK=campuscircle_default
OFFERFORGE_BACKEND_PORT=8081
OFFERFORGE_FRONTEND_PORT=8082
OFFERFORGE_MYSQL_HOST=campuscircle-mysql
OFFERFORGE_MYSQL_PORT=3306
OFFERFORGE_MYSQL_DATABASE=offerforge_db
OFFERFORGE_DB_USERNAME=root
OFFERFORGE_DB_PASSWORD=$DB_PWD
OFFERFORGE_REDIS_HOST=campuscircle-redis
OFFERFORGE_REDIS_PORT=6379
OFFERFORGE_REDIS_PASSWORD=
OFFERFORGE_REDIS_DATABASE=1
OFFERFORGE_ES_HOST=campuscircle-elasticsearch
OFFERFORGE_ES_PORT=9200
OFFERFORGE_SEARCH_ENABLED=true
OFFERFORGE_SEARCH_EMBEDDING_PROVIDER=mock
OFFERFORGE_AI_PROVIDER=mock
OFFERFORGE_QUOTA_ENABLED=true
OFFERFORGE_QUOTA_DAILY_LIMIT=10
APIKEY_ENCRYPT_KEY=$ENC_KEY
EOF
chmod 600 .env
echo ".env written"

# 建库 + 建表
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" -e \
  "CREATE DATABASE IF NOT EXISTS offerforge_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
docker exec -i campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db \
  < /opt/offerforge/src/main/resources/db/schema.sql
echo "--- tables ---"
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -e "SHOW TABLES;"
