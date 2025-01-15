package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class TimeFrameVO {
    private String id;
    @Schema(description = "1滚球，2单式")
    private Integer ballType;
    private String ballName;
    @Schema(description = "1上半，2全场")
    private Integer courseType;
    private String courseName;
    @Schema(description = "时间从-秒")
    private Integer timeFormSec;
    @Schema(description = "到-秒")
    private Integer timeToSec;
}
