package com.offerforge.knowledge;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
