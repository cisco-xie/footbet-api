package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class TypeFilterVO {
    @Schema(description = "不做·让球盘·平手盘")
    private Integer flatPlate;
}
