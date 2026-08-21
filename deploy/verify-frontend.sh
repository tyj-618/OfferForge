#!/usr/bin/env bash
# 验证容器内前端产物包含新功能关键字（产物哈希随构建变化，动态定位文件）
set -u
FILE=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/InterviewView-*.js' | head -1)
echo "asset: $FILE"
docker exec offerforge-frontend ls /usr/share/nginx/html/assets | grep InterviewView
echo "--- keyword counts ---"
for kw in '训练模式' '实战模式' '深度训练' '下一板块' '退出深度训练' '这道题可以专项强化' 'goodPoints' 'improvedAnswer' 'deepTrainingActive' 'Enter 发送' 'mode-select'; do
  n=$(docker exec offerforge-frontend grep -o "$kw" "$FILE" | wc -l)
  echo "$kw: $n"
done
echo "--- backend endpoints (no-auth http codes) ---"
curl -sk -o /dev/null -w "deep-training=%{http_code} " -X POST "https://easyofferforge.com/api/interview/nonexist/deep-training"
curl -sk -o /dev/null -w "deep-training-exit=%{http_code} " -X POST "https://easyofferforge.com/api/interview/nonexist/deep-training/exit"
curl -sk -o /dev/null -w "next-question=%{http_code} " -X POST "https://easyofferforge.com/api/interview/nonexist/next-question"
curl -sk -o /dev/null -w "followup-removed=%{http_code} " -X POST "https://easyofferforge.com/api/interview/nonexist/followup"
curl -sk -o /dev/null -w "auth-me=%{http_code}\n" "https://easyofferforge.com/api/auth/me"
echo "--- sse resilience + user cache keywords in index bundle ---"
IDX=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/index-*.js' | head -1)
for kw in authRetry 40100 'AI 响应超时' '连接已中断' offerforge_user 'deep-training' '/api/auth/me'; do
  n=$(docker exec offerforge-frontend grep -o "$kw" "$IDX" | wc -l)
  echo "$kw: $n"
done
echo "VERIFY-DONE"
