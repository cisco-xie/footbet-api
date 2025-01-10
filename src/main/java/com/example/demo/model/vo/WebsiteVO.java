package com.example.demo.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class WebsiteVO {
    @Schema(description = "新增不传，更新传入")
    private String id;
    @NotBlank(message = "网站名称不能为空")
    @Schema(description = "网站名称")
    private String name;
    @Schema(description = "网站地址")
    private String baseUrl;
    @Schema(description = "是否启用（0否1是）")
    private Integer enable = 0;
    @Schema(description = "是否单式（0否1是）")
    private Integer simplex = 0;
    @Schema(description = "是否滚球（0否1是）")
    private Integer rollingBall = 0;
    @Schema(description = "上盘（0否1是）")
    private Integer hangingWall = 0;
    @Schema(description = "下盘（0否1是）")
    private Integer footwall = 0;
    @Schema(description = "大球（0否1是）")
    private Integer bigBall = 0;
    @Schema(description = "小球（0否1是）")
    private Integer smallBall = 0;
    @Schema(description = "上半（0否1是）")
    private Integer firstHalf = 0;
    @Schema(description = "全场（0否1是）")
    private Integer fullCourt = 0;
    @Schema(description = "赔率类型")
    private Integer oddsType = 1;
    @Schema(description = "单式顺序")
    private Integer simplexOrder = 0;
    @Schema(description = "滚球顺序")
    private Integer rollingOrder = 0;
    @Schema(description = "交收数")
    private BigDecimal settlementNum = BigDecimal.ZERO;
    @Schema(description = "模拟投注")
    private Integer simulateBet = 0;
    @Schema(description = "现金平台")
    private Integer cashPlatform = 0;
    @Schema(description = "回水")
    private BigDecimal backwater = BigDecimal.ZERO;
}
