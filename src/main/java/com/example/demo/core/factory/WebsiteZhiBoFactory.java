package com.example.demo.core.factory;

import com.example.demo.core.sites.zhibo.WebsiteZhiBoLoginHandler;
import org.springframework.stereotype.Component;

/**
 * 智博工厂实现
 */
@Component
public class WebsiteZhiBoFactory implements WebsiteApiFactory {
    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteZhiBoLoginHandler(); // 返回具体的登录处理类
    }

    @Override
    public boolean supports(String siteId) {
        return "1874804932787851264".equalsIgnoreCase(siteId);
    }

}
