package com.example.demo.model.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ProfitDTO {
    @Schema(description = "单式·让球盘")
    private Double singleLetBall;
    @Schema(description = "滚球·让球盘")
    private Double rollingLetBall;
    @Schema(description = "单式·大小盘")
    private Double singleSize;
    @Schema(description = "滚球·大小盘")
    private Double rollingSize;
}
