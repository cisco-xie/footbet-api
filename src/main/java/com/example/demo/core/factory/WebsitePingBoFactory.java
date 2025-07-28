package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.config.OkHttpProxyDispatcher;
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
    private OkHttpProxyDispatcher dispatcher;

    @Resource
    private WebsiteService websiteService;

    @Resource
    private ApiUrlService apiUrlService;

    @Override
    public boolean supports(String siteId) {
        return WebsiteType.PINGBO.getId().equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler checkUsername() {
        return null;
    }

    @Override
    public ApiHandler changeUsername() {
        return null;
    }

    @Override
    public ApiHandler changePwd() {
        return new WebsitePingBoChangePwdHandler(dispatcher, websiteService, apiUrlService); // 返回具体的修改密码处理类
    }

    @Override
    public ApiHandler accept() {
        return new WebsitePingBoAcceptHandler(dispatcher, websiteService, apiUrlService);   // 返回具体的接受协议处理类
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsitePingBoLoginHandler(dispatcher, websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsitePingBoInfoHandler(dispatcher, websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventListHandler() {
        return new WebsitePingBoEventListHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return new WebsitePingBoEventsHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        return new WebsitePingBoEventsOddsHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return new WebsitePingBoStatementHandler(dispatcher, websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return new WebsitePingBoBetUnsettledHandler(dispatcher, websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }

    @Override
    public ApiHandler preferences() {
        return null;         // 平博网站暂不用进行偏好设置
    }

    @Override
    public ApiHandler bet() {
        return new WebsitePingBoBetHandler(dispatcher, websiteService, apiUrlService); // 返回具体的投注处理类
    }

    @Override
    public ApiHandler betPreview() {
        return new WebsitePingBoBetPreviewHandler(dispatcher, websiteService, apiUrlService); // 返回具体的投注预览处理类
    }
}
