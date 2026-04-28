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
import com.example.demo.api.SweepwaterService;
import com.example.demo.core.result.PageResult;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
import com.example.demo.model.vo.bet.BetRetryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "投注列表")
@RequestMapping("/api")
@RestController
public class BetController extends BaseController {

    @Resource
    private BetService betService;

    @Operation(summary = "查询是否存在新的投注")
    @GetMapping("/bets/exist/new")
    public Result<Boolean> existNew(@RequestParam(defaultValue = "0") Integer lastSeenTotal) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getNewRealtimeBetsSince(admin.getUsername(), lastSeenTotal));
    }

    @Operation(summary = "查询是否存在新的角球投注")
    @GetMapping("/corner/bets/exist/new")
    public Result<Boolean> existCornerNew(@RequestParam(defaultValue = "0") Integer lastSeenTotal) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getNewCornerRealtimeBetsSince(admin.getUsername(), lastSeenTotal));
    }

    @Operation(summary = "获取实时进单列表")
    @GetMapping("/bets/realtime")
    public Result<PageResult<SweepwaterBetDTO>> getBetsReal(@RequestParam(value = "teamName", required = false) String teamName,
                                                      @RequestParam(defaultValue = "1") Integer pageNum,
                                                      @RequestParam(defaultValue = "500") Integer pageSize) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getRealTimeBets(admin.getUsername(), teamName, pageNum, pageSize));
    }

    @Operation(summary = "获取角球实时进单列表")
    @GetMapping("/corner/bets/realtime")
    public Result<PageResult<SweepwaterBetDTO>> getCornerBetsReal(
            @RequestParam(value = "teamName", required = false) String teamName,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "500") Integer pageSize) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getCornerRealTimeBets(admin.getUsername(), teamName, pageNum, pageSize));
    }

    @Operation(summary = "获取实时进单列表-websocket增量版")
    @GetMapping("/v2/bets/realtime")
    public Result<PageResult<SweepwaterBetDTO>> getBetsReal(
            @RequestParam(value = "teamName", required = false) String teamName,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "500") Integer pageSize,
            @RequestParam(required = false) Integer sinceIndex) {

        AdminLoginDTO admin = getUser();
        return Result.success(betService.getRealTimeBets(admin.getUsername(), teamName, pageNum, pageSize, sinceIndex));
    }

    @GetMapping("/bets/realtime/byKey")
    public Result<SweepwaterBetDTO> getBetByKey(@RequestParam String key) {
        SweepwaterBetDTO dto = betService.getRealTimeBetByKey(key);
        return Result.success(dto);
    }

    @Operation(summary = "获取历史进单列表")
    @GetMapping("/bets/history")
    public Result<PageResult<SweepwaterBetDTO>> getBetsHistory(@RequestParam(value = "teamName", required = false) String teamName,
                                                               @RequestParam(value = "startDate", required = false) String startDate,
                                                               @RequestParam(value = "success", required = false) String success,
                                                               @RequestParam(defaultValue = "1") Integer pageNum,
                                                               @RequestParam(defaultValue = "500") Integer pageSize) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getBets(admin.getUsername(), teamName, startDate, success, pageNum, pageSize));
    }

    @Operation(summary = "清空实时进单列表")
    @GetMapping("/bets/clear")
    public Result clear() {
        AdminLoginDTO admin = getUser();
        betService.betClear(admin.getUsername());
        return Result.success();
    }

    @Operation(summary = "清空角球实时进单列表")
    @GetMapping("/corner/bets/clear")
    public Result cornerClear() {
        AdminLoginDTO admin = getUser();
        betService.cornerBetClear(admin.getUsername());
        return Result.success();
    }

    @Operation(summary = "手动补单")
    @PostMapping("/bets/retry")
    public Result<cn.hutool.json.JSONObject> betRetry(@RequestBody BetRetryVO retryVO) {
        AdminLoginDTO admin = getUser();
        if (retryVO == null || retryVO.getBetId() == null || retryVO.getBetId().isBlank()) {
            return Result.failed(-1, "betId不能为空");
        }
        try {
            return Result.success(betService.retryBetById(admin.getUsername(), retryVO.getBetId()));
        } catch (Exception e) {
            return Result.failed(-1, e.getMessage());
        }
    }

}
