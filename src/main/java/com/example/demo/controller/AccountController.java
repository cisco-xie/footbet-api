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

import cn.hutool.core.bean.BeanUtil;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.HandicapApi;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.ConfigAccountDTO;
import com.example.demo.model.dto.WebSiteDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import com.example.demo.model.vo.WebsiteVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "账号管理")
@RequestMapping("/api")
@RestController
public class AccountController extends BaseController {

    @Resource
    private ConfigAccountService accountService;

    @Resource
    private HandicapApi handicapApi;

    @Operation(summary = "新增账号")
    @PostMapping("/accounts")
    public Result add(@RequestParam String websiteId, @RequestBody ConfigAccountVO configAccountVO) {
        AdminLoginDTO admin = getUser();
        accountService.saveAccount(admin.getUsername(), websiteId, configAccountVO);
        return Result.success();
    }

    @Operation(summary = "删除账号")
    @DeleteMapping("/accounts/{websiteId}/{id}")
    public Result delete(@PathVariable String websiteId, @PathVariable String id) {
        AdminLoginDTO admin = getUser();
        accountService.deleteAccount(admin.getUsername(), websiteId, id);
        return Result.success();
    }

    @Operation(summary = "获取指定网站的账号列表")
    @GetMapping("/accounts/{websiteId}")
    public Result<List<ConfigAccountDTO>> getWebsites(@PathVariable String websiteId) {
        AdminLoginDTO admin = getUser();
        List<ConfigAccountVO> websites = accountService.getAccount(admin.getUsername(), websiteId);
        return Result.success(BeanUtil.copyToList(websites, ConfigAccountDTO.class));
    }

    @Operation(summary = "批量登录 - 一键登录指定网站的账号列表")
    @GetMapping("/login/{websiteId}")
    public Result loginByWebsite(@PathVariable String websiteId) {
        AdminLoginDTO admin = getUser();
        handicapApi.loginByWebsite(admin.getUsername(), websiteId);
        return Result.success();
    }

    @Operation(summary = "单个登录 - 登录指定网站账号")
    @GetMapping("/single-login/{websiteId}/{accountId}")
    public Result singleLogin(@PathVariable String websiteId, @PathVariable String accountId) {
        AdminLoginDTO admin = getUser();
        handicapApi.singleLogin(admin.getUsername(), websiteId, accountId);
        return Result.success();
    }

    @Operation(summary = "一键下线指定网站的账号列表")
    @GetMapping("/logout/{websiteId}")
    public Result logoutByWebsite(@PathVariable String websiteId) {
        AdminLoginDTO admin = getUser();
        accountService.logoutByWebsite(admin.getUsername(), websiteId, null);
        return Result.success();
    }

    @Operation(summary = "单个下线 - 下线指定网站账号")
    @GetMapping("/single-login-out/{websiteId}/{accountId}")
    public Result singleLoginout(@PathVariable String websiteId, @PathVariable String accountId) {
        AdminLoginDTO admin = getUser();
        accountService.logoutByWebsite(admin.getUsername(), websiteId, accountId);
        return Result.success();
    }

    @Operation(summary = "一键启停 - 指定网站的账号列表")
    @GetMapping("/enable/{websiteId}/{enable}")
    public Result enable(@PathVariable String websiteId, @PathVariable Integer enable) {
        AdminLoginDTO admin = getUser();
        accountService.enable(admin.getUsername(), websiteId, enable);
        return Result.success();
    }

    @Operation(summary = "一键启停自动登录 - 指定网站的账号列表")
    @GetMapping("/enable/auto-login/{websiteId}/{enable}")
    public Result autoLoginEnable(@PathVariable String websiteId, @PathVariable Integer enable) {
        AdminLoginDTO admin = getUser();
        accountService.autoLoginEnable(admin.getUsername(), websiteId, enable);
        return Result.success();
    }

}
