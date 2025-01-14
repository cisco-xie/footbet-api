package com.example.demo.model.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class TypeFilterDTO {
    @Schema(description = "不做·让球盘·平手盘")
    private Integer flatPlate;
}
