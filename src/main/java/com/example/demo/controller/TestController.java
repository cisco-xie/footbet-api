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

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.BetService;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.api.RealtimeIndexService;
import com.example.demo.api.SweepwaterService;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.config.SuccessBasedLimitManager;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    @Resource
    private SimpMessagingTemplate messagingTemplate;
    @Resource
    private SuccessBasedLimitManager limitManager;
    @Resource
    private RealtimeIndexService realtimeIndexService;

    @Operation(summary = "登录所有盘口账号")
    @GetMapping("/login")
    public Result add() {
        api.login();
        return Result.success();
    }

    @Operation(summary = "登录单个盘口账号")
    @GetMapping("/login/{websiteId}/{accountId}")
    public Result singleLogin(@PathVariable String websiteId, @PathVariable String accountId) {
        AdminLoginDTO admin = getUser();
        api.singleLogin(admin.getUsername(), websiteId, accountId);
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
    public Result eventsOddsList(@RequestParam String websiteId, @RequestParam(value = "lid", required = false) String lid, @RequestParam(value = "ecid", required = false) String ecid) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.eventsOdds(admin.getUsername(), websiteId, lid, ecid));
    }

    @Operation(summary = "获取网站联赛相关赔率")
    @GetMapping("/odds/info")
    public Result eventsOddsInfo(@RequestParam String websiteId, @RequestParam(value = "lid", required = false) String lid, @RequestParam(value = "ecid", required = false) String ecid) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.oddsInfo(admin.getUsername(), websiteId, lid, ecid));
    }

    @Operation(summary = "扫水")
    @GetMapping("/sweepwater")
    public Result sweepwater() {
        AdminLoginDTO admin = getUser();
        sweepwaterService.sweepwater(null, null, IdUtil.getSnowflakeNextIdStr());
        return Result.success();
    }

    @Operation(summary = "扫水")
    @GetMapping("/bet")
    public Result bet() {
        AdminLoginDTO admin = getUser();
        //betService.bet(admin.getUsername());
        return Result.success();
    }

    @Operation(summary = "未结单列表")
    @GetMapping("/unset")
    public Result unsettled(@RequestParam String websiteId, @RequestParam String accountId) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.unsettled(admin.getUsername(), websiteId, accountId));
    }

    @Operation(summary = "去重")
    @GetMapping("/dedup")
    public Result dedup(@RequestParam String websiteId) {
        AdminLoginDTO admin = getUser();
        accountService.deduplicateLargeAccountList(admin.getUsername(), websiteId);
        return Result.success();
    }

    @Operation(summary = "模拟角球投注成功")
    @GetMapping("/ws/debug/push")
    public Result pushTest(@RequestParam String user) {
        SweepwaterBetDTO dto = new SweepwaterBetDTO();
        dto.setId(IdUtil.fastSimpleUUID());
        dto.setBetSuccessA(true);
        dto.setWebsiteNameA("模拟盘口");
        dto.setCreateTime(LocalDateTimeUtil.format(LocalDateTime.now(), DatePattern.NORM_DATETIME_PATTERN));
        dto.setLeagueNameA("模拟联赛");
        dto.setTeamA("主队");
        dto.setTeamB("客队");
        dto.setProject("corner");
        dto.setOddsA(new java.math.BigDecimal("1.5"));
        dto.setType("corner");

        JSONObject betInfoA = new JSONObject();
        betInfoA.set("team", "主队");
        betInfoA.set("league", "模拟联赛");
        betInfoA.set("odds", "1.5");
        betInfoA.set("handicap", "0");
        betInfoA.set("oddsId", "test_odds_id");
        betInfoA.set("selectionId", "test_selection_id");
        dto.setBetInfoA(betInfoA);

        String json = JSONUtil.toJsonStr(dto);

        String realTimeKey = KeyUtil.genKey(
                RedisConstants.PLATFORM_BET_CORNER_PREFIX,
                user,
                "realtime",
                dto.getId()
        );

        realtimeIndexService.pushRealtimeIndex(user, realTimeKey, json, "corner");
        log.info("模拟角球投注成功已推送 user={}, key={}", user, realTimeKey);
        return Result.success();
    }

    @Operation(summary = "删除用户历史所有注单列表")
    @DeleteMapping("/bet")
    public Result delBet() {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.deleteAllUserBetHistory(admin.getUsername()));
    }

    @Operation(summary = "投注次数间隔限制-手动触发状态打印")
    @GetMapping("/limit/print")
    public Result limitManager() {
        limitManager.triggerManualPrint();
        return Result.success();
    }

    @Operation(summary = "投注次数间隔限制-获取所有limitKey的列表")
    @GetMapping("/limit/list")
    public Result getAllLimitKeys() {
        return Result.success(limitManager.getAllLimitKeys());
    }

}
