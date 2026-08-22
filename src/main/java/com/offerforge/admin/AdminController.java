package com.offerforge.admin;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.feedback.FeedbackService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理台接口：管理员由环境变量 OFFERFORGE_ADMIN_USERNAMES 指定的用户名认定。
 * whoami 仅需登录（供前端判断是否展示管理入口）；其余接口非管理员一律 40300。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;
    private final CurrentUserService currentUserService;
    private final FeedbackService feedbackService;

    public AdminController(AdminService adminService, CurrentUserService currentUserService,
                           FeedbackService feedbackService) {
        this.adminService = adminService;
        this.currentUserService = currentUserService;
        this.feedbackService = feedbackService;
    }

    @GetMapping("/whoami")
    public ApiResponse<Map<String, Boolean>> whoami(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(Map.of("admin", adminService.isAdmin(userId)));
    }

    @GetMapping("/stats")
    public ApiResponse<AdminStats> stats(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.stats());
    }

    @GetMapping("/users")
    public ApiResponse<AdminUserPage> users(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(adminService.listUsers(keyword, page, size));
    }

    @PostMapping("/users/{id}/ban")
    public ApiResponse<Boolean> ban(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") Long id) {
        adminService.requireAdmin(authorization);
        adminService.ban(id);
        return ApiResponse.success(true);
    }

    @PostMapping("/users/{id}/unban")
    public ApiResponse<Boolean> unban(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") Long id) {
        adminService.requireAdmin(authorization);
        adminService.unban(id);
        return ApiResponse.success(true);
    }

    /** 问题反馈列表（倒序分页）：含提交用户、类型、正文与图片 */
    @GetMapping("/feedbacks")
    public ApiResponse<FeedbackService.FeedbackPage> feedbacks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        adminService.requireAdmin(authorization);
        return ApiResponse.success(feedbackService.listAll(page, size));
    }
}
