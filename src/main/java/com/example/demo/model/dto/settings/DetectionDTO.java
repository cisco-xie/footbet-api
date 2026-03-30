package com.example.demo.model.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class DetectionDTO {
    @Schema(description = "是否开启(0=关闭,1=开启)")
    private Integer enabled;
    @Schema(description = "间隔时间(分钟)")
    private Integer interval;
}
