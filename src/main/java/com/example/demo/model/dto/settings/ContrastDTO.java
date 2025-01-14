package com.example.demo.model.dto.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ContrastDTO {
    @Schema(description = "对比id")
    private String id;
    @Schema(description = "对比网站id-A")
    private String websiteIdA;
    @Schema(description = "对比网站id-B")
    private String websiteIdB;
    @Schema(description = "对比网站name-A")
    private String websiteNameA;
    @Schema(description = "对比网站name-B")
    private String websiteNameB;
}
