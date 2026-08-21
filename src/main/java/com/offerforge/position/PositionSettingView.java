package com.offerforge.position;

import java.util.List;

/** 岗位设置视图/更新载荷：当前选中岗位 + 自定义岗位清单 */
public record PositionSettingView(String currentPosition, List<CustomPosition> customPositions) {
}
