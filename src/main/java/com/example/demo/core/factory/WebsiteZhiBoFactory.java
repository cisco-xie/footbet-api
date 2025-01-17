package com.example.demo.core.factory;

import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoInfoHandler;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoLoginHandler;
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
        return "1874804932787851264".equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteZhiBoLoginHandler(); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return new WebsiteZhiBoInfoHandler(websiteService, apiUrlService); // 返回具体的详情处理类
    }

}
