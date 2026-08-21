#!/usr/bin/env bash
# 服务器端：验证「开场深挖/开场评分 + 品牌图标」上线效果
# [1] 健康检查 [2] favicon 与导航栏 logo 资源 [3] 训练模式开场自我介绍评分 [4] 后端开场相关日志
set -uo pipefail
BASE=https://easyofferforge.com

echo "===== [1/4] health ====="
curl -s -m 10 "$BASE/api/health"
echo

echo "===== [2/4] favicon & brand logo assets ====="
curl -s -o /dev/null -w "favicon_http_code=%{http_code} size=%{size_download}\n" -m 10 "$BASE/favicon.png"
INDEX=$(curl -s -m 10 "$BASE/")
echo "$INDEX" | grep -c 'favicon.png' | sed 's/^/index_favicon_refs=/'
JS=$(echo "$INDEX" | grep -oE '/assets/index-[^"]+\.js' | head -1)
if [ -n "$JS" ]; then
    LOGO_ASSET=$(curl -s -m 10 "$BASE$JS" | grep -oE 'logo-[A-Za-z0-9_-]+\.png' | head -1)
    echo "main_js_logo_ref=$LOGO_ASSET"
    curl -s -o /dev/null -w "logo_http_code=%{http_code} size=%{size_download}\n" -m 10 "$BASE/assets/$LOGO_ASSET"
fi

echo "===== [3/4] training opening intro scored ====="
U="verify_opening_$(date +%s)"
curl -s -m 10 -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$U\",\"password\":\"Verify@123456\"}" > /dev/null
TOKEN=$(curl -s -m 10 -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$U\",\"password\":\"Verify@123456\"}" | grep -oE '"token":"[^"]+"' | cut -d'"' -f4)
echo "token_present=$([ -n "$TOKEN" ] && echo yes || echo no)"
SESSION=$(curl -s -m 10 -X POST "$BASE/api/interview/start" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d '{"mode":"training"}' | grep -oE '"sessionId":"[^"]+"' | head -1 | cut -d'"' -f4)
echo "session=$SESSION"
SSE=$(curl -s -m 180 -X POST "$BASE/api/interview/$SESSION/ask" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d '{"message":"面试官您好，我是一名 Java 后端方向的求职者，熟悉 Spring Boot、MyBatis 与 MySQL，独立开发过校园论坛项目，负责用户认证、帖子发布与缓存穿透治理等核心模块。"}')
echo "$SSE" | grep -oE '"score":[0-9.]+' | head -1 | sed 's/^/opening_done_score=/'
echo "$SSE" | grep -oE '"action":"[A-Z_]+"' | head -1 | sed 's/^/opening_action=/'
echo "$SSE" | grep -c 'event:done' | sed 's/^/done_frames=/'

echo "===== [4/4] backend opening-related logs ====="
sudo docker logs --tail 200 offerforge-backend 2>&1 | grep -E 'intro|opening follow' | tail -5
echo "===== DONE ====="
