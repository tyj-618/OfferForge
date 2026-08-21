package com.offerforge.position;

import java.util.List;

/** 用户自定义岗位：岗位名 + 绑定的技术栈标签（官方/自定义标签名） */
public record CustomPosition(String name, List<String> tags) {
}
