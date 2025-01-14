package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class OddsScanVO {
    @Schema(description = "开始关注扫描水位 从")
    private Double waterLevelFrom;
    @Schema(description = "开始关注扫描水位 到")
    private Double waterLevelTo;
    @Schema(description = "每轮分析对比（次数）")
    private Integer contrastNum;
    @Schema(description = "每轮对比最多失败（次数）")
    private Integer failedNum;
}
