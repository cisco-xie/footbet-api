
package com.example.demo.controller;

import com.example.demo.api.SettingsService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.settings.ContrastDTO;
import com.example.demo.model.dto.settings.OddsScanDTO;
import com.example.demo.model.vo.settings.ContrastVO;
import com.example.demo.model.vo.settings.OddsScanVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "足球项目")
@RequestMapping("/api/settings")
@RestController
public class SettingsOddsScanController extends BaseController {

    @Resource
    private SettingsService settingsService;

    @Operation(summary = "新增常规设置-赔率扫描")
    @PostMapping("/odds-scan")
    public Result add(@RequestBody OddsScanVO oddsScanVO) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增网站
        settingsService.saveOddsScan(admin.getUsername(), oddsScanVO);
        return Result.success();
    }

    @Operation(summary = "获取用户常规设置-赔率扫描列表")
    @GetMapping("/odds-scan")
    public Result<OddsScanDTO> getOddsScan() {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        return Result.success(settingsService.getOddsScan(admin.getUsername()));
    }

}
