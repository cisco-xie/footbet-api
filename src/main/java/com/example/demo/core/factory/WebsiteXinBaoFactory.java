package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.sites.pingbo.WebsitePingBoBetUnsettledHandler;
import com.example.demo.core.sites.pingbo.WebsitePingBoEventsHandler;
import com.example.demo.core.sites.pingbo.WebsitePingBoEventsOddsHandler;
import com.example.demo.core.sites.pingbo.WebsitePingBoInfoHandler;
import com.example.demo.core.sites.xinbao.*;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoInfoHandler;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 新宝工厂实现
 */
@Component
public class WebsiteXinBaoFactory implements WebsiteApiFactory {
    @Resource
    private WebsiteService websiteService;

    @Resource
    private ApiUrlService apiUrlService;

    @Override
    public boolean supports(String siteId) {
        return "1877702689064243200".equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteXinBaoLoginHandler(websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsiteXinBaoInfoHandler(websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return new WebsiteXinBaoEventsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        return null; // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return new WebsiteXinBaoStatementHandler(websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return new WebsiteXinBaoBetUnsettledHandler(websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }
}
