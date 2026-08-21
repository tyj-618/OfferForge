package com.offerforge.admin;

/**
 * 管理台统计概览：用户总数、今日新增（按服务器时区自然日）、封禁数。
 */
public record AdminStats(long totalUsers, long todayNew, long bannedUsers) {
}
