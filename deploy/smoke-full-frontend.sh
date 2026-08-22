#!/usr/bin/env bash
# 完整场次正向验证（详细自我介绍确保离开 OPENING）+ 前端产物关键词
set -uo pipefail
API=http://localhost:8081/api
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
SUF=$(date +%s)
EMAIL="smkfull${SUF}@test.local"
USER="smkfull${SUF}"
RNAME=$(docker ps --format '{{.Names}}' | grep -i redis | head -1)
docker exec "$RNAME" redis-cli -n 1 set "email:code:$EMAIL" 135790 EX 300 > /dev/null
printf '{"email":"%s","code":"135790","username":"%s","password":"123456"}' "$EMAIL" "$USER" > /tmp/reg.json
curl -s -m 10 -X POST -H 'Content-Type: application/json' -d @/tmp/reg.json $API/auth/register | jget '.code'; echo " <- register code (expect 0)"
TOKEN=$(curl -s -m 10 -X POST -H 'Content-Type: application/json' -d '{"username":"'"$USER"'","password":"123456"}' $API/auth/login | jget '.data.token')
AUTH="Authorization: Bearer $TOKEN"
curl -s -m 20 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/knowledge/import > /dev/null
Q0=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_before=$Q0"
SID=$(curl -s -m 15 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/start | jget '.data.sessionId')
echo "sessionId=$SID"
# 生产限流：ask 10 次/60 秒滑动窗口，需节奏控制（每 5 秒一次 ≤ 12 次/分钟）
ask_sse() {
  sleep 5
  curl -s -m 60 -X POST -H "$AUTH" -H 'Content-Type: application/json' -H 'Accept: text/event-stream' -d "{\"message\":\"$1\"}" $API/interview/$SID/ask > /dev/null
}
INTRO='面试官您好，我叫小明，有三年 Java 后端开发经验，主要做过电商订单系统和支付模块，熟悉 Spring Boot、MySQL 与 Redis，请开始提问。'
ask_sse "$INTRO"
# 回答需包含具体职责与可验证细节，避免 LLM 反复追问导致停留在 OPENING
declare -a ANSWERS
ANSWERS[1]='我在该项目中独立负责订单状态机设计与订单创建、支付回调接口的开发，用 MySQL 分库分表支撑日均 200 万订单，通过 Redis Lua 脚本实现库存扣减的原子操作，解决了高并发下的超卖问题，上线后订单链路 P99 延迟从 800ms 降到 220ms。'
ANSWERS[2]='典型难点是支付回调的幂等处理：我设计了基于业务单号的唯一索引加乐观锁方案，用 Redis SETNX 做防重窗口，回调到达先查重再落库，配合定时对账任务补偿，三个月线上零重复扣款事故。'
ANSWERS[3]='性能优化方面：我把热点商品的库存缓存到 Redis 并用 Lua 脚本原子扣减，订单写库改为异步落库加消息队列削峰，数据库慢查询通过覆盖索引和分页优化消除，整体下单 TPS 从 600 提升到 2400。'
ANSWERS[4]='分布式事务上我采用本地消息表加最终一致性方案：订单服务写本地消息表后由定时任务投递到 MQ，库存服务消费并幂等扣减，失败自动重试并告警，避免了强一致 Seata 带来的性能损耗。'
ANSWERS[5]='稳定性建设上我接入了 Sentinel 限流降级，对下游支付网关做了熔断与超时兜底，核心接口补充了单元行测试与压测脚本，发布流程用灰度加回滚预案保障，大促期间系统零故障。'
ANSWERS[6]='分库分表策略上按用户 ID 哈希分 16 库 64 表，扩容用双写加校验的平滑方案；跨分片事务用本地消息表保证最终一致。'
ANSWERS[7]='该项目是我在字节跳动电商中台团队的正式工作项目，岗位是后端开发工程师，2022 年 7 月入职至 2025 年 6 月，所在部门负责订单与支付中台。'
ANSWERS[8]='我本科毕业于电子科技大学软件工程专业，2022 年毕业；实习期间在美团基础架构组参与过服务治理平台的开发，起止时间是 2021 年 6 月到 2022 年 3 月。'
ANSWERS[9]='大促峰值 QPS 约 8 万，Sentinel 单机限流阈值按压测容量的 80% 设定，熔断后返回缓存的商品基本信息并引导稍后重试。'
for i in $(seq 1 20); do
  STATE=$(curl -s -m 10 -H "$AUTH" $API/interview/$SID/status | jget '.data.state')
  ASKED=$(curl -s -m 10 -H "$AUTH" $API/interview/$SID/status | jget '.data.askedCount')
  echo "round=$i state=$STATE asked=$ASKED"
  if [ "$STATE" = "CLOSING" ] || [ "$STATE" = "FINISHED" ]; then break; fi
  MSG=${ANSWERS[$i]:-'我在项目中独立负责该模块的方案设计与核心代码实现，包括接口定义、数据模型设计、性能压测与线上排障，落地了缓存、限流、幂等等手段，有完整的监控告警与灰度发布流程，可量化指标为响应时间与吞吐量提升数据。'}
  ask_sse "$MSG"
done
ask_sse '谢谢面试官，期待反馈。'
FIN=$(curl -s -m 120 -X POST -H "$AUTH" -H 'Content-Type: application/json' -d '{}' $API/interview/$SID/finish)
echo "finish archived=$(echo "$FIN" | jget '.data.archived') score=$(echo "$FIN" | jget '.data.report.overallScore') (expect true/number)"
Q1=$(curl -s -m 10 -H "$AUTH" $API/quota | jget '.data.remaining')
echo "remaining_final=$Q1 (expect Q0-1=$((Q0-1)))"
echo "history_total=$(curl -s -m 10 -H "$AUTH" "$API/report/history?page=0&size=10" | jget '.data.totalElements') (expect 1)"
echo "===== frontend keyword check ====="
IV=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/InterviewView-*.js' | head -1)
TV=$(docker exec offerforge-frontend sh -c 'ls /usr/share/nginx/html/assets/TrainingView-*.js' | head -1)
echo "InterviewView asset: $IV"
echo "TrainingView asset: $TV"
echo "iv_short_note=$(docker exec offerforge-frontend grep -c '也不会记录到历史' "$IV") (expect >=1)"
echo "tv_short_note=$(docker exec offerforge-frontend grep -c '未计入训练历史' "$TV") (expect >=1)"
echo "===== FULL SMOKE DONE ====="
