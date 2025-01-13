package com.example.demo.core.factory;

import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工厂管理器
 * 动态选择不同网站的工厂
 */
@Component
public class WebsiteFactoryManager {
    @Resource
    private List<WebsiteApiFactory> factories;

    public WebsiteApiFactory getFactory(String siteId) {
        return factories.stream()
                .filter(factory -> factory.supports(siteId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No factory found for siteId: " + siteId));
    }
}
