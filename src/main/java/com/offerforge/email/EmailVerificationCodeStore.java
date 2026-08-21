package com.offerforge.email;

/**
 * 邮箱验证码状态存储：验证码本体、发送防刷记录、错误锁定三类 key。
 * Redis 实现用于生产（多实例共享），内存实现用于测试与无 Redis 环境。
 */
public interface EmailVerificationCodeStore {

    /** 保存验证码（覆盖旧值），TTL 5 分钟 */
    void saveCode(String email, String code);

    /** 读取当前有效验证码，过期/不存在返回 null */
    String getCode(String email);

    /** 校验成功后删除验证码，防止重放 */
    void removeCode(String email);

    /** 60 秒防刷标记是否生效（同一邮箱一分钟内只允许发一次） */
    boolean hasRecentSendMark(String email);

    /** 写入 60 秒防刷标记 */
    void markSent(String email);

    /** 记录一次验证码错误；达到上限时置为锁定状态并返回 true */
    boolean recordFailure(String email);

    /** 该邮箱是否处于锁定状态（错误超 5 次锁定 15 分钟） */
    boolean isLocked(String email);

    /** 校验成功后清除错误计数 */
    void clearFailures(String email);
}
