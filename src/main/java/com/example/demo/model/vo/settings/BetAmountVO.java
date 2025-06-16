package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class BetAmountVO {
    @Schema(description = "金额类型(1常规金额,2单注限额·百分比,3固定金额(两边),4随机金额)")
    private Integer type;
    @Schema(description = "智博金额")
    private BigDecimal amountZhiBo;
    @Schema(description = "平博金额")
    private BigDecimal amountPingBo;
    @Schema(description = "新二金额")
    private BigDecimal amountXinEr;
}
