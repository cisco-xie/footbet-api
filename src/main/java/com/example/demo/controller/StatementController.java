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

import com.example.demo.api.HandicapApi;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@Slf4j
@Tag(name = "账户账目")
@RequestMapping("/api")
@RestController
public class StatementController extends BaseController {

    @Resource
    private HandicapApi handicapApi;

    @Operation(summary = "获取账目列表")
    @GetMapping("/statements/{websiteId}/{accountId}")
    public Result statement(@PathVariable String websiteId, @PathVariable String accountId) {
        if ("undefined".equals(websiteId) || "undefined".equals(accountId)) {
            return Result.success();
        }
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        return Result.success(handicapApi.statement(admin.getUsername(), websiteId, accountId));
    }

    @Operation(summary = "获取账目列表")
    @GetMapping("/bet/unsettled/{websiteId}/{accountId}")
    public Result unsettled(@PathVariable String websiteId, @PathVariable String accountId) {
        if ("undefined".equals(websiteId) || "undefined".equals(accountId)) {
            return Result.success();
        }
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        return Result.success(handicapApi.unsettled(admin.getUsername(), websiteId, accountId));
    }
}
