
package com.example.demo.controller;

import com.example.demo.api.SettingsService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.settings.BetAmountDTO;
import com.example.demo.model.dto.settings.ProfitDTO;
import com.example.demo.model.vo.settings.BetAmountVO;
import com.example.demo.model.vo.settings.ProfitVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "足球项目")
@RequestMapping("/api/settings")
@RestController
public class SettingsBetAmoutController extends BaseController {

    @Resource
    private SettingsService settingsService;

    @Operation(summary = "新增常规设置-投注金额")
    @PostMapping("/bet-amount")
    public Result add(@RequestBody BetAmountVO betAmountVO) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增网站
        settingsService.saveBetAmout(admin.getUsername(), betAmountVO);
        return Result.success();
    }

    @Operation(summary = "获取用户常规设置-投注金额")
    @GetMapping("/bet-amount")
    public Result<BetAmountDTO> getBetAmout() {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        return Result.success(settingsService.getBetAmout(admin.getUsername()));
    }

}
