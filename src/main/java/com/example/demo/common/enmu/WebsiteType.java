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
    XINBAO("1877702689064243200", "", "新2"),
    SBO("1877702689064243001", "", "盛帆");

    private final String id;
    private final String games;
    private final String description;

    // 使用静态 Map 来缓存 lottery 和描述的映射关系，避免每次遍历枚举值
    private static final Map<String, WebsiteType> LOTTERY_MAP = new HashMap<>();
    private static final Map<String, String> DESCRIPTION_MAP = new HashMap<>();

    // 静态代码块用于初始化映射
    static {
        for (WebsiteType type : values()) {
            LOTTERY_MAP.put(type.getId(), type);
            DESCRIPTION_MAP.put(type.getDescription(), type.getGames());
        }
    }

    // 获取所有游戏类型的映射
    public static Map<String, String> getAllGameRulesWithDescriptions() {
        return new HashMap<>(DESCRIPTION_MAP); // 返回副本，避免外部修改
    }

    // 根据id获取枚举实例
    public static WebsiteType getById(String id) {
        return LOTTERY_MAP.get(id); // 快速查找
    }
}

