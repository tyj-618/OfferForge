#!/usr/bin/env bash
# 服务器端：脱离 SSH 会话后台执行重部署（避免 ssh 断开导致半途而废）
cd /opt/offerforge || exit 1
rm -f /tmp/redeploy-short.log
setsid nohup bash deploy/redeploy-update.sh > /tmp/redeploy-short.log 2>&1 < /dev/null &
echo "launched pid=$!"
