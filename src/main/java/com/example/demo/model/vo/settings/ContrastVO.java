package com.example.demo.model.vo.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ContrastVO {
    private String id;
    @NotBlank(message = "对比网站不能为空")
    @Schema(description = "对比网站id-A")
    private String websiteIdA;
    @NotBlank(message = "对比网站不能为空")
    @Schema(description = "对比网站id-B")
    private String websiteIdB;
    @Schema(description = "对比网站name-A")
    private String websiteNameA;
    @Schema(description = "对比网站name-B")
    private String websiteNameB;
}
