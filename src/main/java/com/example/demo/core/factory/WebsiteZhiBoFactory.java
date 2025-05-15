package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.core.sites.pingbo.WebsitePingBoEventsHandler;
import com.example.demo.core.sites.xinbao.WebsiteXinBaoBetUnsettledHandler;
import com.example.demo.core.sites.zhibo.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 智博工厂实现
 */
@Component
public class WebsiteZhiBoFactory implements WebsiteApiFactory {
    @Resource
    private WebsiteService websiteService;

    @Resource
    private ApiUrlService apiUrlService;

    @Override
    public boolean supports(String siteId) {
        return WebsiteType.ZHIBO.getId().equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteZhiBoLoginHandler(websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsiteZhiBoInfoHandler(websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return new WebsiteZhiBoEventsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        return new WebsiteZhiBoEventsOddsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return new WebsiteZhiBoStatementHandler(websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return new WebsiteZhiBoBetUnsettledHandler(websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }

    @Override
    public ApiHandler bet() {
        return new WebsiteZhiBoBetHandler(websiteService, apiUrlService); // 返回具体的投注结果处理类
    }

    @Override
    public ApiHandler orderView() {
        return null; // 智博网站不需要投注预览
    }
}
