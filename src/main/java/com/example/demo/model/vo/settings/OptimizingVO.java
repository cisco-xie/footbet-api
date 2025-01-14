package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class OptimizingVO {
    @Schema(description = "不投注对头单(以先进单位的为基准)")
    private Integer contrastNum;
}
