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

import com.example.demo.api.BetService;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.api.SweepwaterService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;


@Slf4j
@Tag(name = "测试")
@RequestMapping("/api")
@RestController
public class TestController extends BaseController {

    @Resource
    private HandicapApi api;
    @Resource
    private SweepwaterService sweepwaterService;
    @Resource
    private BetService betService;
    @Resource
    private ConfigAccountService accountService;

    @Operation(summary = "登录所有盘口账号")
    @GetMapping("/login")
    public Result add() {
        api.login();
        return Result.success();
    }

    @Operation(summary = "获取所有盘口账号额度")
    @GetMapping("/info")
    public Result info() {
        api.info();
        return Result.success();
    }

    @Operation(summary = "获取网站联赛相关赔率")
    @GetMapping("/odds")
    public Result eventsOdds(@RequestParam String websiteId, @RequestParam(value = "lid", required = false) String lid, @RequestParam(value = "ecid", required = false) String ecid) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.eventsOdds(admin.getUsername(), websiteId, lid, ecid));
    }

    @Operation(summary = "扫水")
    @GetMapping("/sweepwater")
    public Result sweepwater() {
        AdminLoginDTO admin = getUser();
        sweepwaterService.sweepwater(admin.getUsername(), null);
        return Result.success();
    }

    @Operation(summary = "扫水")
    @GetMapping("/bet")
    public Result bet() {
        AdminLoginDTO admin = getUser();
        //betService.bet(admin.getUsername());
        return Result.success();
    }

    @Operation(summary = "去重")
    @GetMapping("/dedup")
    public Result dedup(@RequestParam String websiteId) {
        AdminLoginDTO admin = getUser();
        accountService.deduplicateLargeAccountList(admin.getUsername(), websiteId);
        return Result.success();
    }

}
