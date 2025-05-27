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
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.bet.SweepwaterBetDTO;
import com.example.demo.model.dto.sweepwater.SweepwaterDTO;
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

    @Operation(summary = "获取实时进单列表")
    @GetMapping("/bets/realtime")
    public Result<List<SweepwaterBetDTO>> getBetsReal(@RequestParam(value = "teamName", required = false)  String teamName) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getRealTimeBets(admin.getUsername(), teamName));
    }

    @Operation(summary = "获取历史进单列表")
    @GetMapping("/bets/history")
    public Result<List<SweepwaterBetDTO>> getBetsHistory(@RequestParam(value = "teamName", required = false)  String teamName, @RequestParam(value = "startDate", required = false)  String startDate) {
        AdminLoginDTO admin = getUser();
        return Result.success(betService.getBets(admin.getUsername(), teamName, startDate));
    }

    @Operation(summary = "清空实时进单列表")
    @GetMapping("/bets/clear")
    public Result clear() {
        AdminLoginDTO admin = getUser();
        betService.betClear(admin.getUsername());
        return Result.success();
    }

}
