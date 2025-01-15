package com.example.demo.core.factory;

import com.example.demo.core.sites.xinbao.WebsiteXinBaoLoginHandler;
import com.example.demo.core.sites.zhibo.WebsiteZhiBoInfoHandler;
import org.springframework.stereotype.Component;

/**
 * 新宝工厂实现
 */
@Component
public class WebsiteXinBaoFactory implements WebsiteApiFactory {
    @Override
    public boolean supports(String siteId) {
        return "1877702689064243200".equalsIgnoreCase(siteId);
    }

    @Override
    public ApiHandler getLoginHandler() {
        return new WebsiteXinBaoLoginHandler(); // 返回具体的登录处理类
    }

    @Override
    public ApiHandler getInfoHandler() {
        return null; // 返回具体的详情处理类
    }
}
