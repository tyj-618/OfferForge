package com.offerforge.knowledge;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final CurrentUserService currentUserService;

    public KnowledgeController(KnowledgeService knowledgeService, CurrentUserService currentUserService) {
        this.knowledgeService = knowledgeService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/import")
    public ApiResponse<KnowledgeService.ImportSummary> importBuiltin(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireUserId(authorization);
        return ApiResponse.success(knowledgeService.importBuiltinKnowledge());
    }

    /** 可见分组：官方 + 本人自定义 */
    @GetMapping("/categories")
    public ApiResponse<KnowledgeService.CategoriesView> categories(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(knowledgeService.visibleCategories(userId));
    }

    /** 我的上传列表（仅本人私有） */
    @GetMapping("/mine")
    public ApiResponse<List<KnowledgeService.OwnedKnowledge>> mine(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(knowledgeService.listMine(userId));
    }

    /** 官方题库列表（全局共享只读）：资源库页按分组筛选与浏览答案，附带本人掌握度标记 */
    @GetMapping("/official")
    public ApiResponse<List<KnowledgeService.OwnedKnowledge>> official(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(knowledgeService.listOfficial(userId));
    }

    /** 批量删除本人上传的资料；仅删除归属本人的条目，返回实际删除条数 */
    @PostMapping("/batch-delete")
    public ApiResponse<BatchDeleteResult> batchDelete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody BatchDeleteRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        int deleted = knowledgeService.batchDeleteOwned(userId,
                request == null ? null : request.ids());
        return ApiResponse.success(new BatchDeleteResult(deleted));
    }

    /** 批量迁移本人上传的资料到指定标签；仅迁移归属本人的条目，返回实际迁移条数 */
    @PostMapping("/batch-move")
    public ApiResponse<BatchMoveResult> batchMove(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody BatchMoveRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        int moved = knowledgeService.batchMoveOwned(userId,
                request == null ? null : request.ids(),
                request == null ? null : request.category());
        return ApiResponse.success(new BatchMoveResult(moved));
    }

    /** 分组推荐：按简历技能关键词打分，resumeId 可空（默认最新简历） */
    @GetMapping("/recommend")
    public ApiResponse<List<String>> recommend(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "resumeId", required = false) Long resumeId) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(knowledgeService.recommendCategories(userId, resumeId));
    }

    /**
     * 上传资料：仅接受 .md/.txt（大小上限由 multipart 配置控制，超限抛 MaxUploadSizeExceededException 由全局异常处理）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeService.UploadSummary> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", required = false) String category) {
        Long userId = currentUserService.requireUserId(authorization);
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "缺少文件名");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".txt")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "仅支持 .md / .txt 格式的资料文件");
        }
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件内容为空");
        }
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "文件读取失败");
        }
        return ApiResponse.success(knowledgeService.uploadKnowledge(userId, filename, content, category));
    }

    /** 删除本人上传的资料；不存在或非本人返回 NOT_FOUND */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = currentUserService.requireUserId(authorization);
        knowledgeService.deleteOwned(userId, id);
        return ApiResponse.success(null);
    }

    /** 迁移本人资料到指定分组：分组名可为已有标签或新建标签；不存在或非本人返回 NOT_FOUND */
    @PutMapping("/{id}/category")
    public ApiResponse<Void> updateCategory(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody CategoryUpdateRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        knowledgeService.moveOwned(userId, id, request == null ? null : request.category());
        return ApiResponse.success(null);
    }

    public record BatchDeleteRequest(List<Long> ids) {
    }

    public record BatchDeleteResult(int deleted) {
    }

    public record BatchMoveRequest(List<Long> ids, String category) {
    }

    public record BatchMoveResult(int moved) {
    }

    public record CategoryUpdateRequest(String category) {
    }
}
