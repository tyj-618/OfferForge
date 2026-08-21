package com.offerforge.report;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/report")
public class ReportController {

    private static final int MAX_PAGE_SIZE = 50;

    private final ReportService reportService;
    private final CurrentUserService currentUserService;

    public ReportController(ReportService reportService, CurrentUserService currentUserService) {
        this.reportService = reportService;
        this.currentUserService = currentUserService;
    }

    /**
     * 单次面试报告详情。
     */
    @GetMapping("/{interviewId}")
    public ApiResponse<InterviewReport> report(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String interviewId) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(reportService.getReport(userId, interviewId));
    }

    /**
     * 历史面试列表：按开始时间倒序分页；mode=training/practice 时仅返回对应模式记录。
     */
    @GetMapping("/history")
    public ApiResponse<Page<InterviewHistoryItem>> history(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String mode) {
        Long userId = currentUserService.requireUserId(authorization);
        String normalizedMode = normalizeMode(mode);
        int cappedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return ApiResponse.success(
                reportService.history(userId, normalizedMode, PageRequest.of(Math.max(0, page), cappedSize)));
    }

    /** mode 仅接受 training/practice，其余非空值拒绝；空值返回全部记录 */
    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        if ("training".equals(mode) || "practice".equals(mode)) {
            return mode;
        }
        throw new BusinessException(ErrorCode.PARAM_ERROR, "mode 仅支持 training/practice");
    }

    /**
     * 进步曲线：最近 limit 次面试的综合分趋势（时间正序）。
     */
    @GetMapping("/progress")
    public ApiResponse<List<InterviewProgressPoint>> progress(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "10") int limit) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(reportService.progress(userId, limit));
    }
}
