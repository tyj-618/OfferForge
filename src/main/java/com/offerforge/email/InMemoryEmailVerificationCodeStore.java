package com.offerforge.email;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试/降级实现：进程内存存储（单实例场景语义等价于 Redis 版）。
 */
@Component
@Profile("!redis")
public class InMemoryEmailVerificationCodeStore implements EmailVerificationCodeStore {

    private record Entry(String value, long expireAtMillis) {
        boolean expired(Clock clock) {
            return clock.millis() >= expireAtMillis;
        }
    }

    private final Map<String, Entry> codes = new ConcurrentHashMap<>();
    private final Map<String, Entry> sendMarks = new ConcurrentHashMap<>();
    private final Map<String, Entry> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryEmailVerificationCodeStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void saveCode(String email, String code) {
        codes.put(email, new Entry(code, clock.millis() + 5 * 60_000L));
    }

    @Override
    public String getCode(String email) {
        Entry entry = codes.get(email);
        return entry == null || entry.expired(clock) ? null : entry.value();
    }

    @Override
    public void removeCode(String email) {
        codes.remove(email);
    }

    @Override
    public boolean hasRecentSendMark(String email) {
        Entry entry = sendMarks.get(email);
        return entry != null && !entry.expired(clock);
    }

    @Override
    public void markSent(String email) {
        sendMarks.put(email, new Entry("1", clock.millis() + 60_000L));
    }

    @Override
    public boolean recordFailure(String email) {
        Entry entry = failures.get(email);
        long count = entry == null || entry.expired(clock) ? 1 : Long.parseLong(entry.value()) + 1;
        failures.put(email, new Entry(String.valueOf(count), clock.millis() + 15 * 60_000L));
        return count > RedisEmailVerificationCodeStore.MAX_FAILURES;
    }

    @Override
    public boolean isLocked(String email) {
        Entry entry = failures.get(email);
        return entry != null && !entry.expired(clock)
                && Long.parseLong(entry.value()) > RedisEmailVerificationCodeStore.MAX_FAILURES;
    }

    @Override
    public void clearFailures(String email) {
        failures.remove(email);
    }
}
