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

import com.example.demo.api.SettingsService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.settings.ContrastDTO;
import com.example.demo.model.vo.settings.ContrastVO;
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
public class SettingsContrastController extends BaseController {

    @Resource
    private SettingsService settingsService;

    @Operation(summary = "新增常规设置-对比分析")
    @PostMapping("/contrasts")
    public Result add(@RequestBody ContrastVO contrastVO) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增网站
        settingsService.saveContrast(admin.getUsername(), contrastVO);
        return Result.success();
    }

    @Operation(summary = "删除常规设置-对比分析")
    @DeleteMapping("/contrasts/{id}")
    public Result delete(@PathVariable String id) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法删除网站
        settingsService.deleteContrast(admin.getUsername(), id);
        return Result.success();
    }

    @Operation(summary = "获取用户常规设置-对比分析列表")
    @GetMapping("/contrasts")
    public Result<List<ContrastDTO>> getContrasts() {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        return Result.success(settingsService.getContrasts(admin.getUsername()));
    }

}
