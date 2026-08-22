#!/usr/bin/env bash
# 服务器端：为生产 .env 幂等补齐管理员账号相关变量（存在则跳过）
set -uo pipefail
cd /opt/offerforge

if grep -q '^OFFERFORGE_ADMIN_USERNAMES=' .env; then
    echo "OFFERFORGE_ADMIN_USERNAMES already present"
else
    echo 'OFFERFORGE_ADMIN_USERNAMES=admin' >> .env
    echo "OFFERFORGE_ADMIN_USERNAMES appended"
fi

if grep -q '^OFFERFORGE_ADMIN_BOOTSTRAP_ENABLED=' .env; then
    echo "OFFERFORGE_ADMIN_BOOTSTRAP_ENABLED already present"
else
    echo 'OFFERFORGE_ADMIN_BOOTSTRAP_ENABLED=true' >> .env
    echo "OFFERFORGE_ADMIN_BOOTSTRAP_ENABLED appended"
fi

chmod 600 .env
echo "--- admin vars in .env ---"
grep -E '^OFFERFORGE_ADMIN_' .env
echo "===== ENV DONE ====="
