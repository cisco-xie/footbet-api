// OrderVO.java

// YApi QuickType插件生成，具体参考文档:https://plugins.jetbrains.com/plugin/18847-yapi-quicktype/documentation

package com.example.demo.model.vo;
import lombok.Data;

import java.util.List;

@Data
public class OrderVO {
    private String token;
    private String drawNumber;
    private boolean ignore;
    private String lottery;
    private List<Bet> bets;
    private boolean fastBets;
}

// Bet.java

// YApi QuickType插件生成，具体参考文档:https://plugins.jetbrains.com/plugin/18847-yapi-quicktype/documentation

@Data
class Bet {
    private String game;
    private long amount;
    private String contents;
    private double odds;
    private String title;
}
