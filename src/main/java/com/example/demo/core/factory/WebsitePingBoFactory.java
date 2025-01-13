package com.example.demo.core.factory;

import com.example.demo.core.sites.zhibo.WebsitePingBoLoginHandler;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoLoginHandler;
import org.springframework.stereotype.Component;

/**
 * 平博工厂实现
 */
@Component
public class WebsitePingBoFactory implements WebsiteApiFactory {
    @Override
    public ApiHandler getLoginHandler() {
        return new WebsitePingBoLoginHandler(); // 返回具体的登录处理类
    }

    @Override
    public boolean supports(String siteId) {
        return "1874805533324103680".equalsIgnoreCase(siteId);
    }

}
