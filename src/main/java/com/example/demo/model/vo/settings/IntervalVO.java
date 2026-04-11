package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class IntervalVO {
    @Schema(description = "赛事·投注成功·锁定(秒)")
    private Integer betSuccessSec;
    @Schema(description = "角球·投注间隔(秒)")
    private Integer cornerBetSuccessSec;
}
