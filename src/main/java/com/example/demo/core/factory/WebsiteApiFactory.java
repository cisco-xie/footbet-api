package com.example.demo.core.factory;

/**
 * 网站工厂API
 */
public interface WebsiteApiFactory {
    boolean supports(String siteId);        // 判断是否支持该 siteId
    ApiHandler checkUsername();             // 检测账户名是否通过
    ApiHandler changeUsername();            // 修改账户名
    ApiHandler changePwd();                 // 修改密码
    ApiHandler accept();                    // 同意协议
    ApiHandler getLoginHandler();           // 登录
    ApiHandler getInfoHandler();            // 账号详情
    ApiHandler getEventListHandler();       // 赛事列表-用于网站查看赛事列表
    ApiHandler getEventsHandler();          // 赛事列表-用于球队字典查询
    ApiHandler getEventsOddsHandler();      // 赛事列表-带相关赔率
    ApiHandler getStatementsHandler();      // 账目列表
    ApiHandler getBetUnsettledHandler();    // 投注列表-未结算
    ApiHandler getBetSettledHandler();      // 投注列表-已结算
    ApiHandler preferences();               // 偏好设置
    ApiHandler bet();                       // 投注
    ApiHandler betPreview();                // 投注预览
//    ApiHandler getMatchListHandler();  // 获取比赛列表
//    ApiHandler getOddsHandler();       // 获取赔率
//    ApiHandler getBetHandler();        // 下注
}
