package com.example.demo.model.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class LimitDTO {
    @Schema(description = "同场比赛·投注限制(注)")
    private Integer betLimitGame;
    @Schema(description = "同场同比分·投注限制(注)")
    private Integer betLimitScore;
}
