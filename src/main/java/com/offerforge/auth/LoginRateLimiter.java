package com.offerforge.auth;

import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 5;
    private static final long WINDOW_MILLIS = 15 * 60 * 1000L;

    private final Map<String, Deque<Long>> failureTimestamps = new ConcurrentHashMap<>();

    public void checkAllowed(String username) {
        Deque<Long> failures = failureTimestamps.get(username);
        if (failures == null) {
            return;
        }
        purgeExpired(failures);
        if (failures.size() >= MAX_FAILURES) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "登录失败次数过多，请 15 分钟后再试");
        }
    }

    public void recordFailure(String username) {
        Deque<Long> failures = failureTimestamps.computeIfAbsent(username, ignored -> new ConcurrentLinkedDeque<>());
        failures.addLast(System.currentTimeMillis());
        purgeExpired(failures);
    }

    public void clear(String username) {
        failureTimestamps.remove(username);
    }

    private void purgeExpired(Deque<Long> failures) {
        long threshold = System.currentTimeMillis() - WINDOW_MILLIS;
        while (!failures.isEmpty() && failures.peekFirst() < threshold) {
            failures.pollFirst();
        }
    }
}
