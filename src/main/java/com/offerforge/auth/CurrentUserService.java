package com.offerforge.auth;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CurrentUserService {

    private final TokenStore tokenStore;

    public CurrentUserService(TokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    public Long requireUserId(String authorization) {
        String token = tokenStore.resolveBearerToken(authorization)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        return tokenStore.findUserId(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    public Optional<Long> findUserId(String authorization) {
        return tokenStore.resolveBearerToken(authorization)
                .flatMap(tokenStore::findUserId);
    }
}
