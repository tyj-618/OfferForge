#!/usr/bin/env bash
# 补充验证：index bundle 中的 auth/me 路径与容器内产物哈希对比
set -u
IDX=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/index-*.js' | head -1)
echo "index asset: $IDX"
for kw in 'auth/me' 'auth/register' 'offerforge_user' 'currentUser'; do
  n=$(docker exec offerforge-frontend grep -o "$kw" "$IDX" | wc -l)
  echo "$kw: $n"
done
echo "--- container assets ---"
docker exec offerforge-frontend ls /usr/share/nginx/html/assets | grep -E '^(index|InterviewView)'
