package com.example.demo.model.vo;

import cn.hutool.json.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfigAccountVO {
    private String id;
    @Schema(description = "所属网站id")
    private String websiteId;
    @Schema(description = "所属网站地址")
    private String websiteUrl;
    // 投注类型 add/update
    @NotBlank(message = "操作类型必选")
    private String operationType;
    @Schema(description = "可用投注额")
    private BigDecimal betCredit;
    @Schema(description = "是否启用（0否1是）")
    private Integer enable = 0;
    @Schema(description = "自动登录（0否1是）")
    private Integer autoLogin = 1;
    @Schema(description = "用途(0通用 1扫水 2投注)")
    private Integer useType = 0;
    @NotBlank(message = "盘口账号不能为空")
    private String account;
    @NotBlank(message = "盘口密码不能为空")
    private String password;
    @Schema(description = "安全码")
    private String safetyCode;
    @Schema(description = "指定网址")
    private String specifyWebsite;
    @Schema(description = "投注金额倍数 默认1")
    private double multiple = 1;
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
    // 是否登录
    private Integer isTokenValid = 0;
    // 盘口token json
    private JSONObject token;
    @Schema(description = "执行信息")
    private String executeMsg;

    public void setAccount(String account) {
        this.account = account == null ? null : account.trim();
    }

    public void setPassword(String password) {
        this.password = password == null ? null : password.trim();
    }

    public void setProxyHost(String proxyHost) {
        this.proxyHost = proxyHost == null ? null : proxyHost.trim();
    }

    public void setProxyUsername(String proxyUsername) {
        this.proxyUsername = proxyUsername == null ? null : proxyUsername.trim();
    }

    public void setProxyPassword(String proxyPassword) {
        this.proxyPassword = proxyPassword == null ? null : proxyPassword.trim();
    }
}
