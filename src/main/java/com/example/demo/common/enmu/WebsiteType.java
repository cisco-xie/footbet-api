package com.example.demo.common.enmu;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 游戏类型定义枚举常量
 * 暂时只获取单号参数
 */
@Getter
@AllArgsConstructor
public enum WebsiteType {
    ZHIBO("1874804932787851264", "", "智博"),
    PINGBO("1874805533324103680", "", "平博"),
    XINBAO("1877702689064243200", "", "新宝");

    private final String lottery;
    private final String games;
    private final String description;

    // 获取所有游戏类型的映射
    public static Map<String, String> getAllGameRulesWithDescriptions() {
        Map<String, String> params = new HashMap<>();
        for (WebsiteType type : values()) {
            params.put(type.getDescription(), type.getGames());
        }
        return params;
    }

    // 根据code获取枚举实例
    public static WebsiteType getByLottery(String lottery) {
        for (WebsiteType type : values()) {
            if (type.getLottery().equals(lottery)) {
                return type;
            }
        }
        return null;
    }
}

