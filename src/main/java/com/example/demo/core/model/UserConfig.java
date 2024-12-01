package com.example.demo.core.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UserConfig {
    private String id;
    private String baseUrl;
    private String account;
    private String password;
    // 距离封盘时间内下注
    private Integer closeTime;
    // 账号盘口类型 A盘 B盘 C盘 D盘
    private String accountType;
    // 当前登录是否有效 0否 1是
    private Integer isTokenValid;
    // 是否自动登录 0否 1是
    private Integer isAutoLogin = 1;
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
    private String token;
    // 当前额度
    private BigDecimal balance;
    // 未结算金额
    private BigDecimal betting;
    // 今日流水
    private BigDecimal amount;
    // 今日盈亏
    private BigDecimal result;

    public UserConfig(String account1) {
        this.account = account1;
    }
}
