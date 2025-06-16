package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class LimitVO {
    @Schema(description = "同场比赛·投注限制(注)")
    private Integer betLimitGame;
    @Schema(description = "同场同比分·投注限制(注)")
    private Integer betLimitScore;
    @Schema(description = "单边投注(开启后只投注最新赔率的一边)")
    private Integer unilateralBet;
    @Schema(description = "选边(1=旧, 2=新)")
    private Integer unilateralBetType;
    @Schema(description = "限定网站(1=智博, 2=平博, 3=新二)")
    private String websiteLimit;
}
