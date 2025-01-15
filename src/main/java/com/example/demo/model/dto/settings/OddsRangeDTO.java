package com.example.demo.model.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class OddsRangeDTO {
    @Schema(description = "网站id")
    private String websiteId;
    @Schema(description = "网站name")
    private String websiteName;
    @Schema(description = "赔率大于")
    private Double oddsGreater;
    @Schema(description = "赔率小于")
    private Double oddsLess;
}
