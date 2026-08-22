#!/usr/bin/env bash
# 服务器端：认证改造冒烟验证（admin 双入口登录 + 管理权限 + 注册/忘记密码契约）
set -uo pipefail
BASE=http://localhost:8081

echo "===== [1] admin login by username ====="
R1=$(curl -s -m 10 -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"123456"}')
echo "$R1" | head -c 300; echo
TOKEN=$(echo "$R1" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

echo "===== [2] admin login by email ====="
curl -s -m 10 -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
    -d '{"username":"3520097134@qq.com","password":"123456"}' | head -c 200; echo

echo "===== [3] admin whoami ====="
curl -s -m 10 $BASE/api/admin/whoami -H "Authorization: Bearer $TOKEN"; echo

echo "===== [4] admin stats (权限) ====="
curl -s -m 10 $BASE/api/admin/stats -H "Authorization: Bearer $TOKEN" | head -c 200; echo

echo "===== [5] register contract: invalid email -> 40000 ====="
curl -s -m 10 -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
    -d '{"email":"not-an-email","code":"123456","username":"smoke_x","password":"123456"}' | head -c 200; echo

echo "===== [6] register contract: missing code -> 40000 ====="
curl -s -m 10 -X POST $BASE/api/auth/register -H 'Content-Type: application/json' \
    -d '{"email":"smoke@example.com","username":"smoke_x","password":"123456"}' | head -c 200; echo

echo "===== [7] reset-password contract: unregistered email -> 40400 ====="
curl -s -m 10 -X POST $BASE/api/auth/reset-password -H 'Content-Type: application/json' \
    -d '{"email":"ghost_smoke@example.com","code":"123456","newPassword":"new-pass-1"}' | head -c 200; echo

echo "===== [8] login-by-code removed -> 404/40100 均可接受 ====="
curl -s -o /dev/null -w "login-by-code http_code=%{http_code}\n" -m 10 -X POST $BASE/api/auth/login-by-code \
    -H 'Content-Type: application/json' -d '{"email":"a@b.com","code":"123456"}'

echo "===== [9] send-code real send to verified address ====="
curl -s -m 15 -X POST $BASE/api/auth/send-code -H 'Content-Type: application/json' \
    -d '{"email":"3520097134@qq.com"}' | head -c 200; echo
echo "===== SMOKE DONE ====="
