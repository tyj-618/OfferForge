package com.offerforge.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 面试岗位设置（每用户一条）：当前选中岗位 + 用户自定义岗位清单。
 * 岗位选择持久保留，直到用户主动更改。
 */
@Entity
@Table(name = "position_setting", uniqueConstraints = {
        @UniqueConstraint(name = "uk_position_setting_user", columnNames = "user_id")
})
public class PositionSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 当前选中的岗位名称（预设或自定义），可为空表示未选择 */
    @Column(name = "current_position", length = 64)
    private String currentPosition;

    /** 自定义岗位清单 JSON（List<CustomPosition>），可为空 */
    @Column(name = "custom_positions_json", columnDefinition = "LONGTEXT")
    private String customPositionsJson;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getCurrentPosition() {
        return currentPosition;
    }

    public void setCurrentPosition(String currentPosition) {
        this.currentPosition = currentPosition;
    }

    public String getCustomPositionsJson() {
        return customPositionsJson;
    }

    public void setCustomPositionsJson(String customPositionsJson) {
        this.customPositionsJson = customPositionsJson;
    }
}
