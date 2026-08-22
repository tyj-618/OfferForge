package com.offerforge.feedback;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问题反馈接口：登录用户图文提交；管理台查看入口在 /api/admin/feedbacks（AdminController）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final CurrentUserService currentUserService;

    public FeedbackController(FeedbackService feedbackService, CurrentUserService currentUserService) {
        this.feedbackService = feedbackService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    public ApiResponse<FeedbackService.FeedbackView> submit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody FeedbackRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(feedbackService.submit(
                userId, request.type(), request.content(), request.images()));
    }

    /** 本人历史反馈：供用户回看自己提交过的内容 */
    @GetMapping("/mine")
    public ApiResponse<List<FeedbackService.FeedbackView>> mine(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(feedbackService.listMine(userId));
    }

    public record FeedbackRequest(String type, String content, List<String> images) {
    }
}
