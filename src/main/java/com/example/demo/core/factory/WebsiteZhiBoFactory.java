package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.WebsiteType;
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
    public ApiHandler checkUsername() {
        return null;
//        return new WebsiteZhiBoCheckUsernameHandler(websiteService, apiUrlService); // 返回具体的检测昵称是否可用处理类
    }

    @Override
    public ApiHandler changeUsername() {
        return null;
//        return new WebsiteZhiBoChangeUsernameHandler(websiteService, apiUrlService); // 返回具体的修改昵称处理类
    }

    @Override
    public ApiHandler changePwd() {
        return null;
//        return new WebsiteZhiBoChangePwdHandler(websiteService, apiUrlService); // 返回具体的修改密码处理类
    }

    @Override
    public ApiHandler accept() {
        return null;
//        return new WebsiteZhiBoAcceptHandler(websiteService, apiUrlService); // 返回具体的同意协议处理类;
    }

    @Override
    public ApiHandler getLoginHandler() {
        return null;
//        return new WebsiteZhiBoLoginHandler(websiteService, apiUrlService); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return null;
//        return new WebsiteZhiBoInfoHandler(websiteService, apiUrlService); // 返回具体的详情处理类
    }

    @Override
    public ApiHandler getEventListHandler() {
        return null;
//        return new WebsiteZhiBoEventListHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsHandler() {
        return null;
//        return new WebsiteZhiBoEventsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getEventsOddsHandler() {
        return null;
//        return new WebsiteZhiBoEventsOddsHandler(websiteService, apiUrlService); // 返回具体的赛事列表处理类
    }

    @Override
    public ApiHandler getStatementsHandler() {
        return null;
//        return new WebsiteZhiBoStatementHandler(websiteService, apiUrlService); // 返回具体的账目列表处理类
    }

    @Override
    public ApiHandler getBetUnsettledHandler() {
        return null;
//        return new WebsiteZhiBoBetUnsettledHandler(websiteService, apiUrlService); // 返回具体的未结算投注列表处理类
    }

    @Override
    public ApiHandler getBetSettledHandler() {
        return null;
    }

    @Override
    public ApiHandler preferences() {
        return null;
//        return new WebsiteZhiBoPreferencesHandler(websiteService, apiUrlService);   // 智博网站进行偏好设置
    }

    @Override
    public ApiHandler bet() {
        return null;
//        return new WebsiteZhiBoBetHandler(websiteService, apiUrlService); // 返回具体的投注结果处理类
    }

    @Override
    public ApiHandler betPreview() {
        return null;
//        return new WebsiteZhiBoBetPreviewHandler(websiteService, apiUrlService); // 返回具体的投注预览结果处理类
    }
}
