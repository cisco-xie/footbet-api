package com.example.demo.model.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigUserVO {
    // 投注类型 add/update
    @NotBlank(message = "操作类型必选")
    private String operationType;
    @NotBlank(message = "盘口地址不能为空")
    private String baseUrl;
    @NotBlank(message = "盘口账号不能为空")
    private String account;
    @NotBlank(message = "盘口密码不能为空")
    private String password;
    // 距离封盘时间内下注 默认20s
    private Integer closeTime = 0;
    // 投注类型 1正投 2反投
    @NotNull(message = "投注类型必选")
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
