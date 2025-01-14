
package com.example.demo.controller;

import com.example.demo.api.SettingsBetService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.vo.settings.IntervalVO;
import com.example.demo.model.vo.settings.LimitVO;
import com.example.demo.model.vo.settings.OptimizingVO;
import com.example.demo.model.vo.settings.TypeFilterVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "软件设置-投注相关")
@RequestMapping("/api/settings/bet")
@RestController
public class SettingsBetController extends BaseController {

    @Resource
    private SettingsBetService settingsBetService;

    @Operation(summary = "投注相关-投注限制 新增")
    @PostMapping("/limit")
    public Result add(@RequestBody LimitVO limitVO) {
        AdminLoginDTO admin = getUser();
        settingsBetService.saveLimit(admin.getUsername(), limitVO);
        return Result.success();
    }

    @Operation(summary = "投注相关-投注限制 获取")
    @GetMapping("/limit")
    public Result<LimitDTO> getLimit() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsBetService.getLimit(admin.getUsername()));
    }

    @Operation(summary = "投注相关-投注间隔时间 新增")
    @PostMapping("/interval")
    public Result add(@RequestBody IntervalVO intervalVO) {
        AdminLoginDTO admin = getUser();
        settingsBetService.saveInterval(admin.getUsername(), intervalVO);
        return Result.success();
    }

    @Operation(summary = "投注相关-投注间隔时间 获取")
    @GetMapping("/interval")
    public Result<IntervalDTO> getInterval() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsBetService.getInterval(admin.getUsername()));
    }

    @Operation(summary = "投注相关-盘口类型过滤 新增")
    @PostMapping("/type-filter")
    public Result add(@RequestBody TypeFilterVO typeFilterVO) {
        AdminLoginDTO admin = getUser();
        settingsBetService.saveTypeFilter(admin.getUsername(), typeFilterVO);
        return Result.success();
    }

    @Operation(summary = "投注相关-盘口类型过滤 获取")
    @GetMapping("/type-filter")
    public Result<TypeFilterDTO> getTypeFilter() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsBetService.getTypeFilter(admin.getUsername()));
    }

    @Operation(summary = "投注相关-针对性优化设定 新增")
    @PostMapping("/optimizing")
    public Result add(@RequestBody OptimizingVO optimizingVO) {
        AdminLoginDTO admin = getUser();
        settingsBetService.saveOptimizing(admin.getUsername(), optimizingVO);
        return Result.success();
    }

    @Operation(summary = "投注相关-针对性优化设定 获取")
    @GetMapping("/optimizing")
    public Result<OptimizingDTO> getOptimizing() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsBetService.getOptimizing(admin.getUsername()));
    }
}
