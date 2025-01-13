package com.example.demo.core.factory;

/**
 * 网站工厂API
 */
public interface WebsiteApiFactory {
    boolean supports(String siteId);  // 判断是否支持该 siteId
    ApiHandler getLoginHandler();       // 登录
//    ApiHandler getMatchListHandler();  // 获取比赛列表
//    ApiHandler getOddsHandler();       // 获取赔率
//    ApiHandler getBetHandler();        // 下注
}
