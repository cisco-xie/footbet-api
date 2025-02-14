package com.example.demo.common.enmu;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站-时间枚举
 */
@Getter
@AllArgsConstructor
public enum ZhiBoSchedulesType {
    EARLYSCHEDULE(1, "Early Schedule", "早盘"),
    TODAYSCHEDULE(2, "Today Schedule", "今天"),
    LIVESCHEDULE(3, "Live Schedule", "滚球");

    private final int id;
    private final String name;
    private final String description;

    // 使用静态 Map 来缓存 ID 和币种的映射关系，避免每次遍历枚举值
    private static final Map<Integer, ZhiBoSchedulesType> ID_MAP = new HashMap<>();
    private static final Map<String, String> DESCRIPTION_MAP = new HashMap<>();

    // 静态代码块用于初始化映射
    static {
        for (ZhiBoSchedulesType type : values()) {
            ID_MAP.put(type.getId(), type);
            DESCRIPTION_MAP.put(type.getDescription(), type.getName());
        }
    }

    // 获取所有币种类型的映射
    public static Map<String, String> getAllGameRulesWithDescriptions() {
        return new HashMap<>(DESCRIPTION_MAP);
    }

    // 根据id获取枚举实例
    public static ZhiBoSchedulesType getById(Integer id) {
        return ID_MAP.get(id);
    }
}


