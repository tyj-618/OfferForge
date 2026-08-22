#!/usr/bin/env bash
# 服务器端 launcher：以脱离会话方式启动重部署，立即返回
cd /opt/offerforge
setsid nohup bash deploy/redeploy-update.sh > /tmp/redeploy-v1.log 2>&1 < /dev/null &
echo "launcher-done pid=$!"
