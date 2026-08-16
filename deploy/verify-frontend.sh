#!/usr/bin/env bash
# 验证容器内前端产物包含本次新功能关键字
set -u
FILE=/usr/share/nginx/html/assets/InterviewView-DkqS-Vtw.js
docker exec offerforge-frontend ls /usr/share/nginx/html/assets | grep InterviewView
echo "--- keyword counts ---"
for kw in '训练模式' '实战模式' '继续深入' '下一题' 'Enter 发送' '正在思考' 'followUpChoiceRequired' 'mode-select'; do
  n=$(docker exec offerforge-frontend grep -o "$kw" "$FILE" | wc -l)
  echo "$kw: $n"
done
echo "--- backend followup endpoints ---"
curl -sk -o /dev/null -w "followup(no-auth)=%{http_code} " -X POST "https://offerforge.joinuninook.com/api/interview/nonexist/followup"
curl -sk -o /dev/null -w "next-question(no-auth)=%{http_code}\n" -X POST "https://offerforge.joinuninook.com/api/interview/nonexist/next-question"
echo "VERIFY-DONE"
