package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.sites.xinbao.*;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * 新宝工厂实现
 */
@Component
public class WebsiteXinBaoFactory implements WebsiteApiFactory {
    @Resource
    private OkHttpProxyDispatcher dispatcher;

    @Resource
    private WebsiteService websiteService;

    @Resource
    private ApiUrlService apiUrlService;

    @Override
    public boolean supports(String siteId) {
        return WebsiteType.XINBAO.getId().equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler checkUsername() {
        return new WebsiteXinBaoCheckUsernameHandler(dispatcher, websiteService, apiUrlService);    // 返回具体的检查用户名处理类
    }

    @Override
    public ApiHandler changeUsername() {
        return new WebsiteXinBaoChangeUsernameHandler(dispatcher, websiteService, apiUrlService);    // 返回具体的修改用户名处理类
    }

    @Override
    public ApiHandler changePwd() {
        return new WebsiteXinBaoChangePwdHandler(dispatcher, websiteService, apiUrlService);    // 返回具体的修改密码处理类
    }

    @Override
    public ApiHandler accept() {
        return null;
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteXinBaoLoginHandler(dispatcher, websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsiteXinBaoInfoHandler(dispatcher, websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventListHandler() {
        return new WebsiteXinBaoEventListHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return new WebsiteXinBaoEventsHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        // return new WebsiteXinBaoEventsOddsHandler(websiteService, apiUrlService); // 返回具体的赛事赔率详情处理类
        return new WebsiteXinBaoEventOddsNewHandler(dispatcher, websiteService, apiUrlService); // 返回具体的赛事赔率详情处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return new WebsiteXinBaoStatementHandler(dispatcher, websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return new WebsiteXinBaoBetUnsettledHandler(dispatcher, websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }

    @Override
    public ApiHandler preferences() {
        return new WebsiteXinBaoPreferencesHandler(dispatcher, websiteService, apiUrlService); // 返回具体的偏好设置处理类
    }

    @Override
    public ApiHandler bet() {
        return new WebsiteXinBaoBetHandler(dispatcher, websiteService, apiUrlService); // 返回具体的投注处理类
    }

    @Override
    public ApiHandler betPreview() {
        return new WebsiteXinBaoBetPreviewHandler(dispatcher, websiteService, apiUrlService); // 返回具体的投注预览处理类
    }
}
