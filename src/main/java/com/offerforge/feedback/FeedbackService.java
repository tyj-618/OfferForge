package com.offerforge.feedback;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.auth.UserEntity;
import com.offerforge.auth.UserRepository;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 问题反馈：登录用户图文提交（图片以 data URL 提交，服务端校验后 JSON 落库），
 * 管理台分页查看全部反馈。防刷：单用户每日提交条数上限。
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private static final Set<String> TYPES = Set.of("BUG", "SUGGESTION", "OTHER");
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_IMAGES = 3;
    /** 单张图为 base64 data URL，字符数上限约对应 1MB 原始图片 */
    private static final int MAX_IMAGE_LENGTH = 1_500_000;
    private static final int MAX_DAILY_SUBMIT = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public FeedbackService(FeedbackRepository feedbackRepository, UserRepository userRepository,
                           ObjectMapper objectMapper) {
        this.feedbackRepository = feedbackRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FeedbackView submit(Long userId, String type, String content, List<String> images) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "请填写反馈内容");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "反馈内容不能超过 " + MAX_CONTENT_LENGTH + " 字");
        }
        Instant startOfToday = LocalDate.now(ZoneId.systemDefault())
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        if (feedbackRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, startOfToday) >= MAX_DAILY_SUBMIT) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "今日反馈提交已达上限，请明天再试");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        List<String> safeImages = validateImages(images);

        FeedbackItem item = new FeedbackItem();
        item.setUserId(userId);
        item.setUsername(user.getUsername());
        item.setEmail(user.getEmail());
        item.setType(normalizeType(type));
        item.setContent(content.trim());
        item.setImages(serializeImages(safeImages));
        feedbackRepository.save(item);
        log.info("feedback submitted userId={} type={} images={}", userId, item.getType(), safeImages.size());
        return toView(item);
    }

    /** 本人历史反馈（倒序）：便于用户查看自己提交过的内容 */
    public List<FeedbackView> listMine(Long userId) {
        return feedbackRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(this::toView)
                .toList();
    }

    /** 管理台查看全部反馈（倒序分页） */
    public FeedbackPage listAll(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Page<FeedbackItem> result = feedbackRepository.findAllByOrderByIdDesc(PageRequest.of(safePage - 1, safeSize));
        List<FeedbackView> items = result.getContent().stream().map(this::toView).toList();
        return new FeedbackPage(items, safePage, safeSize, result.getTotalElements());
    }

    private static String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return TYPES.contains(normalized) ? normalized : "OTHER";
    }

    private static List<String> validateImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (images.size() > MAX_IMAGES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "最多上传 " + MAX_IMAGES + " 张图片");
        }
        for (String image : images) {
            if (image == null || !image.startsWith("data:image/") || image.length() > MAX_IMAGE_LENGTH) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "图片格式不支持或超过 1MB 上限");
            }
        }
        return images;
    }

    private String serializeImages(List<String> images) {
        if (images.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "反馈图片保存失败");
        }
    }

    private List<String> deserializeImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imagesJson, new TypeReference<>() {
            });
        } catch (Exception exception) {
            log.warn("feedback images parse failed: {}", exception.getMessage());
            return List.of();
        }
    }

    private FeedbackView toView(FeedbackItem item) {
        return new FeedbackView(item.getId(), item.getUsername(), item.getEmail(), item.getType(),
                item.getContent(), deserializeImages(item.getImages()),
                item.getCreatedAt() == null ? "" : TIME_FORMAT.format(item.getCreatedAt()));
    }

    public record FeedbackView(Long id, String username, String email, String type, String content,
                               List<String> images, String createdAt) {
    }

    public record FeedbackPage(List<FeedbackView> items, int page, int size, long total) {
    }
}
