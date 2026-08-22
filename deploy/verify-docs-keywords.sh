#!/usr/bin/env bash
# 补验：DocsView chunk 中的 v1.0 文档关键词（懒加载独立 chunk）
set -u
D=/usr/share/nginx/html/assets/DocsView-scijNB35.js
for kw in '隐私与安全' '问题反馈' '更新日志' '提交反馈' 'LeetCode' 'deepseek-v4-flash'; do
  n=$(docker exec offerforge-frontend grep -c "$kw" "$D" || true)
  echo "$kw: $n"
done
echo "===== KW DONE ====="
