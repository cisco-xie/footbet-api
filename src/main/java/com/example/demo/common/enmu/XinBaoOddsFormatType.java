package com.example.demo.common.enmu;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

/**
 * 新二网站-盘口类型币种枚举
 */
@Getter
@AllArgsConstructor
public enum XinBaoOddsFormatType {
    EUR(1, "E", "欧洲盘"),
    IDR(3, "I", "印尼盘"),
    RM(4, "M", "马来盘"),
    HKC(7, "H", "香港盘");

    private final int id;
    private final String currencyCode;
    private final String description;

    // 使用静态 Map 来缓存 ID 和币种的映射关系，避免每次遍历枚举值
    private static final Map<Integer, XinBaoOddsFormatType> ID_MAP = new HashMap<>();
    private static final Map<String, String> DESCRIPTION_MAP = new HashMap<>();

    // 静态代码块用于初始化映射
    static {
        for (XinBaoOddsFormatType type : values()) {
            ID_MAP.put(type.getId(), type);
            DESCRIPTION_MAP.put(type.getDescription(), type.getCurrencyCode());
        }
    }

    // 获取所有币种类型的映射
    public static Map<String, String> getAllGameRulesWithDescriptions() {
        return new HashMap<>(DESCRIPTION_MAP);
    }

    // 根据id获取枚举实例
    public static XinBaoOddsFormatType getById(Integer id) {
        return ID_MAP.get(id);
    }
}


