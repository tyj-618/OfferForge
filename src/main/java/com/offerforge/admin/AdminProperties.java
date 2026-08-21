package com.offerforge.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理员配置：offerforge.admin.usernames（环境变量 OFFERFORGE_ADMIN_USERNAMES，逗号分隔）；
 * 每项可为用户名或邮箱，任一命中即认定为管理员（邮箱兼容验证码自动注册账号）；未配置时无人具备管理员权限。
 */
@ConfigurationProperties(prefix = "offerforge.admin")
public class AdminProperties {

    private List<String> usernames = new ArrayList<>();

    public List<String> getUsernames() {
        return usernames;
    }

    public void setUsernames(List<String> usernames) {
        this.usernames = usernames;
    }
}
