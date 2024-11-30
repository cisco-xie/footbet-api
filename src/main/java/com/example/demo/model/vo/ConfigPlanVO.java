package com.example.demo.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ConfigPlanVO {
    @Schema(description = "新增不传，更新传入")
    private Integer id;
    @NotBlank(message = "游戏类型不能为空")
    @Schema(description = "游戏类型")
    private String lottery;
    @NotBlank(message = "方案名不能为空")
    @Schema(description = "方案名")
    private String name;
    // 是否启用 0否1是
    @NotNull(message = "是否启用不能为空")
    @Schema(description = "是否启用（0否1是）")
    private Integer enable;
    // 位置数组，1到10
    @Size(max = 10, message = "位置数组最多只能包含10个位置")
    @Schema(description = "位置数组，1到10")
    private List<Integer> positions;
    // 正投数量
    @NotNull(message = "正投数量不能为空")
    @Schema(description = "正投数量")
    private Integer positiveNum;
    // 反投数量
    @NotNull(message = "反投数量不能为空")
    @Schema(description = "反投数量")
    private Integer reverseNum;
    // 正投金额
    @NotNull(message = "正投金额不能为空")
    @Schema(description = "正投金额")
    private Integer positiveAmount;
    // 反投金额
    @NotNull(message = "反投金额不能为空")
    @Schema(description = "反投金额")
    private Integer reverseAmount;
}
