package com.example.demo.core.factory;

import com.example.demo.core.sites.zhibo.WebsiteXinBaoLoginHandler;
import org.springframework.stereotype.Component;

/**
 * 新宝工厂实现
 */
@Component
public class WebsiteXinBaoFactory implements WebsiteApiFactory {
    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteXinBaoLoginHandler(); // 返回具体的登录处理类
    }

    @Override
    public boolean supports(String siteId) {
        return "1877702689064243200".equalsIgnoreCase(siteId);
    }

}
