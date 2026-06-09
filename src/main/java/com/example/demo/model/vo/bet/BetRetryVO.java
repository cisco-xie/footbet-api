package com.example.demo.model.vo.bet;

import lombok.Data;

@Data
public class BetRetryVO {
    private String betId;
    private Integer betType; // 1: 滚球 2: 角球
}
