/*
 * Copyright 2013-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.demo.controller;

import com.example.demo.api.SettingsFilterService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.vo.settings.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "软件设置-过滤相关")
@RequestMapping("/api/settings/filter")
@RestController
public class SettingsFilterController extends BaseController {

    @Resource
    private SettingsFilterService settingsFilterService;

    @Operation(summary = "新增过滤相关-赔率范围")
    @PostMapping("/odds-ranges")
    public Result add(@RequestBody OddsRangeVO oddsRangeVO) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增网站
        settingsFilterService.saveOddsRanges(admin.getUsername(), oddsRangeVO);
        return Result.success();
    }

    @Operation(summary = "过滤相关-赔率范围")
    @DeleteMapping("/odds-ranges/{id}")
    public Result delete(@PathVariable String id) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法删除网站
        settingsFilterService.deleteOddsRanges(admin.getUsername(), id);
        return Result.success();
    }

    @Operation(summary = "获取用户过滤相关-赔率范围列表")
    @GetMapping("/odds-ranges")
    public Result<List<OddsRangeDTO>> getOddsRanges() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsFilterService.getOddsRanges(admin.getUsername()));
    }

    @Operation(summary = "新增过滤相关-赛事时间范围")
    @PostMapping("/timeframes")
    public Result add(@RequestBody TimeFrameVO timeFrameVO) {
        AdminLoginDTO admin = getUser();
        settingsFilterService.saveTimeFrames(admin.getUsername(), timeFrameVO);
        return Result.success();
    }

    @Operation(summary = "删除过滤相关-赛事时间范围")
    @DeleteMapping("/timeframes/{id}")
    public Result deleteTimeFrames(@PathVariable String id) {
        AdminLoginDTO admin = getUser();
        settingsFilterService.deleteTimeFrames(admin.getUsername(), id);
        return Result.success();
    }

    @Operation(summary = "获取用户过滤相关-赛事时间范围列表")
    @GetMapping("/timeframes")
    public Result<List<TimeFrameDTO>> getTimeFrames() {
        AdminLoginDTO admin = getUser();
        return Result.success(settingsFilterService.getTimeFrames(admin.getUsername()));
    }
}
