#!/usr/bin/env bash
# 生产迁移（付费计费）：新增 user_wallet / wallet_transaction / recharge_order 三张表，
# 生产 ddl-auto=none，需手工建表；充值开关默认关闭，建表后可随时上线
set -uo pipefail
PW=$(grep ^OFFERFORGE_DB_PASSWORD /opt/offerforge/.env | cut -d= -f2)
MYSQL="docker exec campuscircle-mysql mysql -uroot -p$PW offerforge_db"

echo "===== [1/4] create user_wallet ====="
docker exec -i campuscircle-mysql mysql -uroot -p"$PW" offerforge_db <<'SQL'
CREATE TABLE IF NOT EXISTS user_wallet (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT   NOT NULL,
    balance_cents         BIGINT   NOT NULL DEFAULT 0,
    total_recharged_cents BIGINT   NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_wallet_user_id (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
SQL
echo "create exit=$?"

echo "===== [2/4] create wallet_transaction ====="
docker exec -i campuscircle-mysql mysql -uroot -p"$PW" offerforge_db <<'SQL'
CREATE TABLE IF NOT EXISTS wallet_transaction (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    type                VARCHAR(16)  NOT NULL,
    amount_cents        BIGINT       NOT NULL,
    balance_after_cents BIGINT       NOT NULL,
    ref_no              VARCHAR(64)  NULL,
    detail              VARCHAR(128) NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_wallet_transaction_user_time (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
SQL
echo "create exit=$?"

echo "===== [3/4] create recharge_order ====="
docker exec -i campuscircle-mysql mysql -uroot -p"$PW" offerforge_db <<'SQL'
CREATE TABLE IF NOT EXISTS recharge_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no        VARCHAR(32) NOT NULL,
    user_id         BIGINT      NOT NULL,
    amount_cents    BIGINT      NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    provider        VARCHAR(16) NOT NULL,
    provider_txn_id VARCHAR(64) NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at         DATETIME    NULL,
    UNIQUE KEY uk_recharge_order_order_no (order_no),
    KEY idx_recharge_order_user_time (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
SQL
echo "create exit=$?"

echo "===== [4/4] verify ====="
$MYSQL -e "DESCRIBE user_wallet; DESCRIBE wallet_transaction; DESCRIBE recharge_order;" 2>/dev/null
echo "===== MIGRATE DONE ====="
