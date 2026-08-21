package com.offerforge.admin;

/**
 * 管理台用户列表条目：status 0=正常 1=封禁；createdAt 为 Asia/Shanghai 时区格式化字符串。
 */
public record AdminUserItem(Long id, String username, String nickname, String email,
                            Integer status, String createdAt, boolean admin) {
}
