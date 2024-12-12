package com.example.demo.common.constants;

public class RedisConstants {
    /**
     * 平台登录账号前缀
     */
    public static final String USER_ADMIN_PREFIX = "user:admin";

    /**
     * 令牌前缀
     */
    public static final String USER_TOKEN_PREFIX = "user:token";

    /**
     * 代理前缀
     */
    public static final String USER_PROXY_PREFIX = "user:proxy";

    /**
     * 方案前缀
     */
    public static final String USER_PLAN_PREFIX = "user:plan";
    /**
     * 方案id前缀
     */
    public static final String USER_PLAN_ID_PREFIX = "user:id:plan";
    /**
     * 下注期数前缀 - 用于获取当前期数是否已下注
     */
    public static final String USER_BET_PERIOD_PREFIX = "user:bet:period";
    /**
     * 期数赔率前缀 - 用于获取当前期数的赔率情况
     */
    public static final String USER_BET_ODDS_PREFIX = "user:bet:odds";
    /**
     * 下注参数日志前缀 - 用于获取当前期数下注参数日志
     */
    public static final String USER_BET_PERIOD_REQ_PREFIX = "user:bet:req";
    /**
     * 下注结果日志前缀 - 用于获取当前期数下注结果日志
     */
    public static final String USER_BET_PERIOD_RES_PREFIX = "user:bet:res";
    /**
     * 自动下注前缀 - 用于获取当前是否自动下注
     */
    public static final String USER_BET_AUTO_PREFIX = "user:bet:auto";
}
