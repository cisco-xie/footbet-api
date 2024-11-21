package com.example.demo.core.model;

import lombok.Data;

@Data
public class UserConfig {
    // 投注类型 1正投 2反投
    private Integer betType;
    // 代理类型 1HTTP 2SOCKS
    private Integer proxyType;
    // 代理IP
    private String proxyHost;
    // 代理端口
    private Integer proxyPort;
    // 认证账号
    private String proxyUsername;
    // 认证密码
    private String proxyPassword;
}
