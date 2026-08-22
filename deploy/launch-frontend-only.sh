#!/usr/bin/env bash
# 服务器端：脱离 SSH 会话后台执行重部署（仅前端变更：跳过后端构建）
cd /opt/offerforge || exit 1
rm -f /tmp/redeploy-short.log
setsid nohup env SKIP_BACKEND_BUILD=1 bash deploy/redeploy-update.sh > /tmp/redeploy-short.log 2>&1 < /dev/null &
echo "launched pid=$!"
