#!/usr/bin/env bash
echo "--- .env ---"
if [ -f /opt/offerforge/.env ]; then echo "ENV_EXISTS lines=$(grep -c '=' /opt/offerforge/.env)"; else echo "NO_ENV"; fi
echo "--- database ---"
DB_PWD=$(sudo grep '^CAMPUSCIRCLE_DB_PASSWORD=' /srv/campuscircle/.env | cut -d= -f2-)
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" -N -e "SHOW DATABASES LIKE 'offerforge_db';" 2>/dev/null || echo DB_QUERY_FAILED
echo "--- tables ---"
docker exec campuscircle-mysql mysql -uroot -p"$DB_PWD" offerforge_db -e "SHOW TABLES;" 2>/dev/null || echo NO_TABLES
echo "--- compose file ---"
grep -c campuscircle-mysql /opt/offerforge/docker-compose.prod.yml 2>/dev/null || echo NO_PROD_COMPOSE
