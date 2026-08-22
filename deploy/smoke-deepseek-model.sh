#!/usr/bin/env bash
# 服务器端冒烟：官方模型选择（deepseek-v4-flash 免费档）——价目/开局/凭据路由/短场退还
set -uo pipefail
API=http://localhost:8081/api

# 通用 JSON 路径提取（点分路径，数字段按数组下标；null/缺失输出空串；布尔小写）
jget() {
  python3 -c 'import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
for k in sys.argv[1].strip(".").split("."):
    if d is None: break
    d = d[int(k)] if isinstance(d,list) and k.isdigit() else (d.get(k) if isinstance(d,dict) else None)
if d is None: print("")
elif isinstance(d,bool): print(str(d).lower())
else: print(d)' "$1"
}

echo "===== [0/5] health ====="
curl -s -m 10 $API/health; echo
SUF=$(date +%s)
EMAIL="smkds${SUF}@test.local"
USER="smkds${SUF}"
echo "===== [1/5] register + login ====="
# 验证码存 Redis（email:code:{email}）：预置后直接注册，免真实发信；容器名动态获取，后端用 database=1
RNAME=$(docker ps --format '{{.Names}}' | grep -i redis | head -1)
docker exec "$RNAME" redis-cli -n 1 set "email:code:$EMAIL" 135790 EX 300 > /dev/null
printf '{"email":"%s","code":"135790","username":"%s","password":"123456"}' "$EMAIL" "$USER" > /tmp/reg.json
curl -s -m 10 -X POST -H 'Content-Type: application/json' -d @/tmp/reg.json $API/auth/register; echo
TOKEN=$(curl -s -m 10 -X POST -H 'Content-Type: application/json' -d '{"username":"'"$USER"'","password":"123456"}' $API/auth/login | jget '.data.token')
echo "token_prefix=${TOKEN:0:12}"
AUTH="Authorization: Bearer $TOKEN"
echo "===== [2/5] models: deepseek-v4-flash paidOnly=false ====="
MODELS=$(curl -s -m 10 -H "$AUTH" $API/billing/models)
echo "$MODELS" | python3 -c 'import json,sys
models=json.load(sys.stdin)["data"]
for m in models:
    if m["id"]=="deepseek-v4-flash":
        print("deepseek paidOnly=%s name=%s" % (str(m["paidOnly"]).lower(), m["name"]))'
echo "===== [3/5] interview start with model=deepseek-v4-flash ====="
Q0=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_before=$Q0"
SID=$(curl -s -m 15 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{"model":"deepseek-v4-flash"}' $API/interview/start | jget '.data.sessionId')
echo "sessionId=$SID"
sleep 5
curl -s -m 60 -X POST -H "$AUTH" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d '{"message":"面试官您好，我叫小明，熟悉 Java 后端，请开始提问。"}' $API/interview/$SID/ask > /dev/null
echo "asked intro (1 LLM call via deepseek endpoint)"
echo "===== [4/5] short session finish: refund + backend log model routing ====="
FIN=$(curl -s -m 90 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/$SID/finish)
echo "finish archived=$(echo "$FIN" | jget '.data.archived') (expect false 短场)"
Q1=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_after_finish=$Q1 (expect equal Q0=$Q0)"
echo "--- backend log: start model + llm routing ---"
docker logs --since 3m offerforge-backend 2>&1 | grep "$SID" | grep -E 'interview started|stage=llm' | cut -c1-260 | tail -6
echo "===== [5/5] frontend asset contains 官方模型选择 ====="
SV=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/SettingsView-*.js' | head -1)
echo "SettingsView asset: $SV"
echo "settings_keyword=$(docker exec offerforge-frontend grep -c '官方模型选择' "$SV") (expect >=1)"
echo "===== DEEPSEEK SMOKE DONE ====="
