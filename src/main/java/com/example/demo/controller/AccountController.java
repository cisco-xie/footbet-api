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

    @Operation(summary = "新增账号")
    @PostMapping("/accounts")
    public Result add(@RequestParam String websiteId, @RequestBody ConfigAccountVO configAccountVO) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增账号
        accountService.saveAccount(admin.getUsername(), websiteId, configAccountVO);
        return Result.success();
    }

    @Operation(summary = "删除账号")
    @DeleteMapping("/accounts/{websiteId}/{id}")
    public Result delete(@PathVariable String websiteId, @PathVariable String id) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法删除账号
        accountService.deleteAccount(admin.getUsername(), websiteId, id);
        return Result.success();
    }

    @Operation(summary = "获取指定网站的账号列表")
    @GetMapping("/accounts/{websiteId}")
    public Result<List<ConfigAccountDTO>> getWebsites(@PathVariable String websiteId) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取账号列表
        List<ConfigAccountVO> websites = accountService.getAccount(admin.getUsername(), websiteId);
        return Result.success(BeanUtil.copyToList(websites, ConfigAccountDTO.class));
    }

}
