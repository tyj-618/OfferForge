#!/usr/bin/env bash
# 验证连续语境修复：复现用户截图场景，检查面试官第二轮追问是否基于已知项目深入而非重复问"哪个项目"
set -u
BASE=http://localhost:8081
TS=$(date +%s)
USER=ctx_$TS
curl -s -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" >/dev/null
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"123456\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo "token: ${TOKEN:0:12}..."
curl -s -X POST $BASE/api/knowledge/import -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' >/dev/null
SID=$(curl -s -X POST $BASE/api/interview/start -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}' | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
echo "session: $SID"

ask() {
  # $1=消息；输出 SSE 正文
  curl -s -X POST $BASE/api/interview/$SID/ask -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
    -d "{\"message\":\"$1\"}"
}

echo "===== round 1: 自我介绍（含项目名 UniNook，缺技术栈） ====="
R1=$(ask "你好，我是张三，就读于南京大学软件工程，现有线上全栈项目——UniNook")
echo "$R1" | tail -c 600
echo

echo "===== round 2: 省略主体的补充（项目由我一人实现+技术栈，缺具体职责） ====="
R2=$(ask "项目完全由我一人实现，核心的技术栈有spring boot、redis、mysql、mybatis、rocketmq等")
echo "$R2" | tail -c 600
echo

# 直接在第二轮 SSE 原文上判定（chunk 内含中文引号时 sed 提取不可靠）
echo "===== judge ====="
if echo "$R2" | grep -q '哪个项目'; then
  echo "BAD: 第二轮仍在问哪个项目，语境未生效"
else
  echo "OK: 第二轮未重复询问项目名称"
fi
if echo "$R2" | grep -q 'UniNook'; then
  echo "OK: 第二轮回复引用了已知项目 UniNook"
else
  echo "NOTE: 第二轮未显式提及 UniNook（人工复核上方文本）"
fi
echo "$R2" | grep -q '"state":"OPENING"' && echo "state: OPENING（继续追问）" || echo "state: 已推进（信息判定充分）"
echo "===== VERIFY END ====="
