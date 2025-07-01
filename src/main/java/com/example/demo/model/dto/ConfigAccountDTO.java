package com.example.demo.model.dto;

import cn.hutool.json.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ConfigAccountDTO {
    private String id;
    @Schema(description = "所属网站id")
    private String websiteId;
    @Schema(description = "所属网站地址")
    private String websiteUrl;
    @Schema(description = "可用投注额")
    private BigDecimal betCredit;
    @Schema(description = "是否启用（0否1是）")
    private Integer enable = 0;
    @Schema(description = "自动登录（0否1是）")
    private Integer autoLogin = 1;
    @Schema(description = "用途(0通用 1扫水 2投注)")
    private Integer useType = 0;
    @Schema(description = "盘口账号")
    private String account;
    @Schema(description = "盘口密码")
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
    private Integer isTokenValid;
    // 盘口token json
    private JSONObject token;
    @Schema(description = "执行信息")
    private String executeMsg;

}
