package com.example.demo.core.factory;

/**
 * 网站工厂API
 */
public interface WebsiteApiFactory {
    boolean supports(String siteId);        // 判断是否支持该 siteId
    ApiHandler getLoginHandler();           // 登录
    ApiHandler getInfoHandler();            // 账号详情
    ApiHandler getEventsHandler();          // 赛事列表
    ApiHandler getEventsOddsHandler();      // 赛事列表-带相关赔率
    ApiHandler getStatementsHandler();      // 账目列表
    ApiHandler getBetUnsettledHandler();    // 投注列表-未结算
    ApiHandler bet();                       // 投注
    ApiHandler orderView();                 // 投注预览
//    ApiHandler getMatchListHandler();  // 获取比赛列表
//    ApiHandler getOddsHandler();       // 获取赔率
//    ApiHandler getBetHandler();        // 下注
}
