package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.sites.pingbo.WebsitePingBoLoginHandler;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoInfoHandler;
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
        return new WebsitePingBoLoginHandler(); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsiteZhiBoInfoHandler(websiteService, apiUrlService); // 返回具体的详情处理类
    }
}
