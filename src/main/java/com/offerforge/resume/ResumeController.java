package com.offerforge.resume;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 简历管理接口：创建/更新、列表、按用户查询、按 section 查询（供 Function Calling）、删除。
 * 路径中的 userId 必须等于当前登录用户，保证只能访问自己的简历。
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService resumeService;
    private final CurrentUserService currentUserService;

    public ResumeController(ResumeService resumeService, CurrentUserService currentUserService) {
        this.resumeService = resumeService;
        this.currentUserService = currentUserService;
    }

    /** 创建/更新简历（id 为空创建；结构化字段全空且带 rawText 时后端 LLM 解析） */
    @PostMapping
    public ApiResponse<ResumeResponse> save(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ResumeRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(resumeService.save(userId, request));
    }

    /** 纯文本解析预览（不落库）：前端粘贴简历原文后回填表单 */
    @PostMapping("/parse")
    public ApiResponse<ResumeRequest> parse(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, String> body) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(resumeService.parseRawText(body == null ? null : body.get("rawText")));
    }

    /** 当前用户全部简历（按更新时间倒序），供面试前选择 */
    @GetMapping("/list")
    public ApiResponse<List<ResumeSummary>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(resumeService.list(userId));
    }

    /** 按 id 获取简历详情（仅本人），供前端编辑页加载指定简历 */
    @GetMapping("/detail/{resumeId}")
    public ApiResponse<ResumeResponse> getDetail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long resumeId) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(resumeService.getOwned(userId, resumeId));
    }

    /** 获取用户简历（最近更新的一份） */
    @GetMapping("/{userId}")
    public ApiResponse<ResumeResponse> getByUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long userId) {
        Long currentUserId = currentUserService.requireUserId(authorization);
        requireSameUser(currentUserId, userId);
        return ApiResponse.success(resumeService.latest(currentUserId));
    }

    /** 获取简历特定部分（纯文本）：section 可选 education/skills/projects/internships/selfIntroduction/all */
    @GetMapping("/{userId}/section/{section}")
    public ApiResponse<String> getSection(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long userId,
            @PathVariable String section,
            @RequestParam(value = "projectIndex", required = false) Integer projectIndex) {
        Long currentUserId = currentUserService.requireUserId(authorization);
        requireSameUser(currentUserId, userId);
        return ApiResponse.success(resumeService.renderSection(currentUserId, null, section, projectIndex));
    }

    /** 删除简历（仅本人） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = currentUserService.requireUserId(authorization);
        resumeService.delete(userId, id);
        return ApiResponse.success(null);
    }

    private void requireSameUser(Long currentUserId, Long pathUserId) {
        if (!currentUserId.equals(pathUserId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问他人简历");
        }
    }
}
