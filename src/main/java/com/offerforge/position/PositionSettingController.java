package com.offerforge.position;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerforge.auth.CurrentUserService;
import com.offerforge.common.ApiResponse;
import com.offerforge.common.ErrorCode;
import com.offerforge.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 面试岗位设置：当前选中岗位 + 用户自定义岗位（岗位与绑定技术栈标签持久保存）。
 */
@RestController
@RequestMapping("/api/interview/position-setting")
public class PositionSettingController {

    private static final Logger log = LoggerFactory.getLogger(PositionSettingController.class);

    private static final int MAX_POSITION_NAME_LENGTH = 64;
    private static final int MAX_CUSTOM_POSITIONS = 20;
    private static final int MAX_TAGS_PER_POSITION = 30;
    private static final int MAX_TAG_LENGTH = 32;

    private final PositionSettingRepository repository;
    private final CurrentUserService currentUserService;
    private final ObjectMapper objectMapper;

    public PositionSettingController(PositionSettingRepository repository,
                                     CurrentUserService currentUserService,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<PositionSettingView> get(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = currentUserService.requireUserId(authorization);
        return ApiResponse.success(repository.findByUserId(userId)
                .map(this::toView)
                .orElseGet(() -> new PositionSettingView(null, List.of())));
    }

    @PutMapping
    public ApiResponse<PositionSettingView> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody PositionSettingView request) {
        Long userId = currentUserService.requireUserId(authorization);
        String currentPosition = normalizePositionName(request.currentPosition());
        List<CustomPosition> customPositions = normalizeCustomPositions(request.customPositions());

        PositionSetting entity = repository.findByUserId(userId).orElseGet(() -> {
            PositionSetting created = new PositionSetting();
            created.setUserId(userId);
            return created;
        });
        entity.setCurrentPosition(currentPosition);
        entity.setCustomPositionsJson(serialize(customPositions));
        return ApiResponse.success(toView(repository.save(entity)));
    }

    private PositionSettingView toView(PositionSetting entity) {
        return new PositionSettingView(entity.getCurrentPosition(), deserialize(entity.getCustomPositionsJson()));
    }

    private String normalizePositionName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_POSITION_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "岗位名称过长");
        }
        return trimmed;
    }

    private List<CustomPosition> normalizeCustomPositions(List<CustomPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        if (positions.size() > MAX_CUSTOM_POSITIONS) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "自定义岗位数量超出上限");
        }
        Set<String> seenNames = new HashSet<>();
        List<CustomPosition> normalized = new ArrayList<>(positions.size());
        for (CustomPosition position : positions) {
            String name = position == null ? null : position.name();
            if (name == null || name.isBlank()) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "岗位名称不能为空");
            }
            String trimmed = name.trim();
            if (trimmed.length() > MAX_POSITION_NAME_LENGTH) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "岗位名称过长");
            }
            if (!seenNames.add(trimmed)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "岗位名称重复：" + trimmed);
            }
            normalized.add(new CustomPosition(trimmed, normalizeTags(position.tags())));
        }
        return normalized;
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String trimmed = tag.trim();
            if (trimmed.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "标签过长：" + trimmed);
            }
            seen.add(trimmed);
            if (seen.size() > MAX_TAGS_PER_POSITION) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "单个岗位绑定的标签数量超出上限");
            }
        }
        return List.copyOf(seen);
    }

    private String serialize(List<CustomPosition> positions) {
        try {
            return objectMapper.writeValueAsString(positions);
        } catch (Exception e) {
            log.error("序列化自定义岗位失败", e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "岗位设置保存失败");
        }
    }

    private List<CustomPosition> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<CustomPosition> positions = objectMapper.readValue(json, new TypeReference<>() {
            });
            return positions == null ? List.of() : positions;
        } catch (Exception e) {
            log.error("反序列化自定义岗位失败: {}", e.getMessage());
            return List.of();
        }
    }
}
