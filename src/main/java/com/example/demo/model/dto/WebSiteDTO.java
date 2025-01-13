package com.example.demo.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class WebSiteDTO {
    @Schema(description = "新增不传，更新传入")
    private String id;
    @NotBlank(message = "网站名称不能为空")
    @Schema(description = "网站名称")
    private String name;
    @Schema(description = "是否启用（0否1是）")
    private Integer enable;
    @Schema(description = "网站地址")
    private List<String> baseUrls;
    @Schema(description = "是否单式（0否1是）")
    private Integer simplex;
    @Schema(description = "是否滚球（0否1是）")
    private Integer rollingBall;
    @Schema(description = "上盘（0否1是）")
    private Integer hangingWall;
    @Schema(description = "下盘（0否1是）")
    private Integer footwall;
    @Schema(description = "大球（0否1是）")
    private Integer bigBall;
    @Schema(description = "小球（0否1是）")
    private Integer smallBall;
    @Schema(description = "上半（0否1是）")
    private Integer firstHalf;
    @Schema(description = "全场（0否1是）")
    private Integer fullCourt;
    @Schema(description = "赔率类型")
    private Integer oddsType;
    @Schema(description = "单式顺序")
    private Integer simplexOrder;
    @Schema(description = "滚球顺序")
    private Integer rollingOrder;
    @Schema(description = "交收数")
    private BigDecimal settlementNum;
    @Schema(description = "模拟投注")
    private Integer simulateBet;
    @Schema(description = "现金平台")
    private Integer cashPlatform;
    @Schema(description = "回水")
    private Integer backwater;
}
