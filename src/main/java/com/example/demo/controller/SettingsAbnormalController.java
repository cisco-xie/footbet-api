package com.example.demo.controller;

import com.example.demo.api.SettingsAbnormalService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.settings.DetectionDTO;
import com.example.demo.model.vo.settings.DetectionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "软件设置-非正常投注检测")
@RequestMapping("/api/settings/abnormal")
@RestController
public class SettingsAbnormalController extends BaseController {

    @Resource
    private SettingsAbnormalService settingsAbnormalService;

    @Operation(summary = "非正常投注检测-保存配置")
    @PostMapping("/detection")
    public Result saveDetection(@RequestBody DetectionVO detectionVO) {
        AdminLoginDTO admin = getUser();
        settingsAbnormalService.saveDetection(admin.getUsername(), detectionVO);
        return Result.success();
    }

    @Operation(summary = "非正常投注检测-获取配置")
    @GetMapping("/detection")
    public Result<DetectionDTO> getDetection() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsAbnormalService.getDetection(admin.getUsername()));
    }
}
