package com.offerforge.admin;

import java.util.List;

/**
 * 管理台用户分页结果：page 从 1 开始（与前端交互一致）。
 */
public record AdminUserPage(List<AdminUserItem> items, int page, int size, long total) {
}
