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

import com.example.demo.core.support.BaseController;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ConfigService;
import com.example.demo.api.FalaliApi;
import com.example.demo.core.result.Result;
import com.example.demo.model.dto.AdminLoginDTO;
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
public class BasicController extends BaseController {

    @Resource
    private FalaliApi api;

    @Resource
    private ConfigService configService;

    @Operation(summary = "今日汇总")
    @GetMapping("/summary/today")
    public Result summaryToday() {
        AdminLoginDTO admin = getUser();
        return Result.success(api.summaryToday(admin.getUsername()));
    }

    @Operation(summary = "账号列表")
    @GetMapping("/accounts")
    public Result accounts() {
        AdminLoginDTO admin = getUser();
        return Result.success(configService.accounts(admin.getUsername(), null));
    }

    @Operation(summary = "用户配置")
    @PostMapping("/accounts")
    @ResponseBody
    public Result configAccount(@RequestBody @Validated ConfigUserVO userProxy) {
        AdminLoginDTO admin = getUser();
        configService.editAccount(admin.getUsername(), userProxy);
        return Result.success();
    }

    @Operation(summary = "删除账号配置")
    @DeleteMapping("/accounts/{account}")
    public Result deleteAccount(@PathVariable("account") String account) {
        AdminLoginDTO admin = getUser();
        configService.deleteAccount(admin.getUsername(), account);
        return Result.success();
    }

    @Operation(summary = "方案配置")
    @PostMapping("/config/plan")
    @ResponseBody
    public Result configPlan(@RequestBody @Validated ConfigPlanVO plan) {
        AdminLoginDTO admin = getUser();
        configService.plan(admin.getUsername(), plan);
        return Result.success();
    }

    @Operation(summary = "删除方案配置")
    @DeleteMapping("/config/plan")
    @ResponseBody
    public Result planDel(@RequestBody List<ConfigPlanVO> plans) {
        AdminLoginDTO admin = getUser();
        configService.planDel(admin.getUsername(), plans);
        return Result.success();
    }

    @Operation(summary = "获取方案配置")
    @GetMapping("/config/plan")
    public Result configPlan(@RequestParam(value = "lottery", required = false) String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(configService.getAllPlans(admin.getUsername(), lottery));
    }

    @GetMapping("/code")
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

    @Operation(summary = "后台登录")
    @PostMapping("/admin/login")
    @ResponseBody
    public Result adminLogin(@RequestBody AdminLoginVO login) {
        return Result.success(api.adminLogin(login));
    }

    @Operation(summary = "单个下线")
    @GetMapping("/single-login-out")
    @ResponseBody
    public Result batchLoginOut(@RequestParam String account) {
        AdminLoginDTO admin = getUser();
        api.singleLoginOut(admin.getUsername(), account);
        return Result.success();
    }

    @Operation(summary = "批量下线")
    @GetMapping("/batch-login-out")
    @ResponseBody
    public Result batchLoginOut() {
        AdminLoginDTO admin = getUser();
        api.batchLoginOut(admin.getUsername());
        return Result.success();
    }

    @Operation(summary = "单个登录")
    @PostMapping("/single-login")
    @ResponseBody
    public Result batchLogin(@RequestBody LoginVO login) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.singleLogin(admin.getUsername(), login));
    }

    @Operation(summary = "批量登录")
    @PostMapping("/batch-login")
    @ResponseBody
    public Result batchLogin() {
        AdminLoginDTO admin = getUser();
        return Result.success(api.batchLogin(admin.getUsername()));
    }

    @Operation(summary = "获取账号token", description = "此接口需轮询更新，如果data返回空则表示当前token已失效需重新获取")
    @GetMapping("/token")
    public Result token(@RequestParam String account) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.token(admin.getUsername(), account));
    }

    @Operation(summary = "获取账号余额", description = "此接口需轮询更新，如果data返回空则表示当前token已失效需重新获取")
    @GetMapping("/balance")
    public Result account(@RequestParam String account) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.account(admin.getUsername(), account));
    }

    @Operation(summary = "获取期数")
    @GetMapping("/period")
    public Result period(@RequestParam String account, @RequestParam String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.period(admin.getUsername(), account, lottery)));
    }

    @Operation(summary = "获取比率")
    @GetMapping("/odds")
    public Result odds(@RequestParam String account, @RequestParam String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.odds(admin.getUsername(), account, lottery)));
    }

    @Operation(summary = "下注")
    @PostMapping("/bet")
    @ResponseBody
    public Result bet(@RequestBody OrderVO order) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.bet(admin.getUsername(), order));
    }

    @Operation(summary = "自动下注")
    @GetMapping("/auto/bet")
    @ResponseBody
    public Result autoBet() {
        api.autoBet();
        return Result.success();
    }

    @Operation(summary = "获取两周流水记录")
    @GetMapping("/settled")
    @ResponseBody
    public Result history(@RequestParam String account,
                          @RequestParam(value = "settled", required = false) Boolean settled,
                          @RequestParam(value = "pageNo", required = false) Integer pageNo) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.settled(admin.getUsername(), account, settled, pageNo, false)));
    }

    @Operation(summary = "获取两周流水记录")
    @GetMapping("/history")
    @ResponseBody
    public Result history(@RequestParam String account, @RequestParam String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.history(admin.getUsername(), account, lottery)));
    }

    @Operation(summary = "异常投注")
    @GetMapping("/bet/failed")
    @ResponseBody
    public Result history() {
        AdminLoginDTO admin = getUser();
        return Result.success(configService.failedBet(admin.getUsername()));
    }

}