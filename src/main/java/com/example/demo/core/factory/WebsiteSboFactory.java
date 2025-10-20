package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.sites.sbo.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 盛帆工厂实现
 */
@Component
public class WebsiteSboFactory implements WebsiteApiFactory {
    @Resource
    private OkHttpProxyDispatcher dispatcher;

    @Resource
    private WebsiteService websiteService;

    @Resource
    private ApiUrlService apiUrlService;

    @Override
    public boolean supports(String siteId) {
        return WebsiteType.SBO.getId().equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler checkUsername() {
        return null;    // 返回具体的检查用户名处理类
    }

    @Override
    public ApiHandler changeUsername() {
        return null;    // 返回具体的修改用户名处理类
    }

    @Override
    public ApiHandler changePwd() {
        return new WebsiteSboChangPwdHandler(dispatcher, websiteService, apiUrlService);    // 返回具体的修改密码处理类
    }

    @Override
    public ApiHandler accept() {
        return new WebsiteSboAcceptHandler(dispatcher, websiteService, apiUrlService);
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteSboLoginHandler(dispatcher, websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsiteSboBalanceHandler(dispatcher, websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventListHandler() {
        return new WebsiteSboEventListHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return new WebsiteSboEventsHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        return new WebsiteSboEventOddsHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事赔率详情处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return new WebsiteSboStatementHandler(dispatcher, websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return new WebsiteSboBetUnsettledHandler(dispatcher, websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }

    @Override
    public ApiHandler getBetSettledHandler() {
        return null;
    }

    @Override
    public ApiHandler preferences() {
        return null; // 返回具体的偏好设置处理类
    }

    @Override
    public ApiHandler bet() {
        return new WebsiteSboBetHandler(dispatcher, websiteService, apiUrlService); // 返回具体的投注处理类
    }

    @Override
    public ApiHandler betPreview() {
        return new WebsiteSboBetPreviewHandler(dispatcher, websiteService, apiUrlService); // 返回具体的投注预览处理类
    }
}
