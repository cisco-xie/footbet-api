package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.sites.pingbo.*;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoInfoHandler;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoStatementHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 平博工厂实现
 */
@Component
public class WebsitePingBoFactory implements WebsiteApiFactory {

    @Resource
    private WebsiteService websiteService;

    @Resource
    private ApiUrlService apiUrlService;

    @Override
    public boolean supports(String siteId) {
        return "1874805533324103680".equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsitePingBoLoginHandler(websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsitePingBoInfoHandler(websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return new WebsitePingBoEventsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        return new WebsitePingBoEventsOddsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return new WebsitePingBoStatementHandler(websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return new WebsitePingBoBetUnsettledHandler(websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }
}
