package com.offerforge.qa;

import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;
    private final CurrentUserService currentUserService;

    public QaController(QaService qaService, CurrentUserService currentUserService) {
        this.qaService = qaService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/ask")
    public ApiResponse<QaResponse> ask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody QaRequest request) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(qaService.ask(userId, request.question()));
    }
}
