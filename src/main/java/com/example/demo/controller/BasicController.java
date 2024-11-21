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

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ConfigService;
import com.example.demo.api.FalaliApi;
import com.example.demo.core.result.Result;
import com.example.demo.model.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "法拉利项目")
@RequestMapping("/api")
@RestController
public class BasicController {

    @Resource
    private FalaliApi api;

    @Resource
    private ConfigService configService;

    @Operation(summary = "用户配置")
    @PostMapping("/config/user")
    @ResponseBody
    public Result configUser(@RequestBody @Validated ConfigUserVO userProxy) {
        configService.user(userProxy);
        return Result.success();
    }

    @Operation(summary = "方案配置")
    @PostMapping("/config/plan")
    @ResponseBody
    public Result configPlan(@RequestBody @Validated ConfigPlanVO plan) {
        configService.plan(plan);
        return Result.success();
    }

    @Operation(summary = "获取方案配置")
    @GetMapping("/config/plan")
    @ResponseBody
    public Result configPlan() {
        return Result.success(configService.getAllPlans());
    }

    @GetMapping("/code")
    @ResponseBody
    public Object code() {
        return api.code(null);
    }

    @Operation(summary = "一键登录")
    @GetMapping("/login")
    public Result login() {
        String uuid = IdUtil.randomUUID();
        String code = api.code(uuid);
        String params = "type=1&account=cs22222&password=WEwe2323&code=" + code;
        return Result.success(api.login(params, uuid));
    }

    @Operation(summary = "单个登录")
    @PostMapping("/single-login")
    @ResponseBody
    public Result batchLogin(@RequestBody LoginVO login) {
        return Result.success(api.singleLogin(login));
    }

    @Operation(summary = "批量登录")
    @PostMapping("/batch-login")
    @ResponseBody
    public Result batchLogin(@RequestBody List<LoginVO> logins) {
        return Result.success(api.batchLogin(logins));
    }

    @Operation(summary = "获取账号余额", description = "此接口需轮询更新，如果data返回空则表示当前token已失效需重新获取")
    @GetMapping("/account")
    public Result account(@RequestParam String token) {
        return Result.success(JSONUtil.parseArray(api.account(token)));
    }

    @Operation(summary = "获取期数")
    @GetMapping("/period")
    public Result period(@RequestParam String token, @RequestParam String lottery) {
        return Result.success(JSONUtil.parseObj(api.period(token, lottery)));
    }

    @Operation(summary = "获取比率")
    @GetMapping("/odds")
    public Result odds(@RequestParam String account, @RequestParam String token, @RequestParam String lottery) {
        return Result.success(JSONUtil.parseObj(api.odds(account, token, lottery)));
    }

    @Operation(summary = "下注")
    @PostMapping("/bet")
    @ResponseBody
    public Result bet(@RequestBody OrderVO order) {
        return Result.success(api.bet(order));
    }

    @Operation(summary = "获取两周流水记录")
    @PostMapping("/history")
    @ResponseBody
    public Result history(@RequestBody TokenVo token, @RequestParam String lottery) {
        return Result.success(JSONUtil.parseObj(api.history(token, lottery)));
    }

}
