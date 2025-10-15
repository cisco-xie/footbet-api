package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
public class LimitVO {
    @Schema(description = "同场比赛·投注限制(注)")
    private Integer betLimitGame;
    @Schema(description = "同场同比分·投注限制(注)")
    private Integer betLimitScore;
    @Schema(description = "单边投注(开启后只投注最新赔率的一边)")
    private Integer unilateralBet;
    @Schema(description = "单边(1=旧, 2=新)")
    private Integer unilateralBetType;
    @Schema(description = "双边(0=不限, 1=旧, 2=新)")
    private Integer bothSideOption;
    @Schema(description = "指定网站(智博, 平博, 新二)")
    private List<String> websiteLimit;
    @Schema(description = "投注失败重试")
    private Integer retry;
    @Schema(description = "重试次数")
    private Integer retryCount;
}
