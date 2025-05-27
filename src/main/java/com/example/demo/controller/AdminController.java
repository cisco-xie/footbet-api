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

import com.example.demo.api.AdminService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.AdminLoginVO;
import com.example.demo.model.vo.AdminUserBetVO;
import com.example.demo.model.vo.AdminUserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "系统用户相关")
@RequestMapping("/api")
@RestController
public class AdminController extends BaseController {

    @Resource
    private AdminService adminService;

    @Operation(summary = "获取当前登录用户最新信息")
    @GetMapping("/admin")
    public Result get() {
        AdminLoginDTO admin = getUser();
        return Result.success(adminService.getAdmin(admin.getUsername()));
    }

    @Operation(summary = "后台登录")
    @PostMapping("/admin/login")
    @ResponseBody
    public Result adminLogin(@RequestBody AdminLoginVO login) {
        return Result.success(adminService.adminLogin(login));
    }

    @Operation(summary = "修改当前登录用户投注相关配置")
    @PutMapping("/admin/bet")
    @ResponseBody
    public Result put(@RequestBody AdminUserBetVO adminBetVO) {
        AdminLoginDTO admin = getUser();
        adminService.editBet(admin.getUsername(), adminBetVO);
        return Result.success();
    }

    @Operation(summary = "获取期数")
    @GetMapping("/refresh")
    public Result refreshRedis() {
        return Result.success();
    }

    @Operation(summary = "获取后台用户列表")
    @GetMapping("/admin/user")
    public Result adminUser(@RequestParam(value = "group", required = false) String group) {
        return Result.success(adminService.getUsers(group));
    }

    @Operation(summary = "获取后台用户小组列表")
    @GetMapping("/admin/group")
    @ResponseBody
    public Result group() {
        return Result.success(adminService.getGroup());
    }

    @Operation(summary = "创建后台用户小组")
    @PostMapping("/admin/group")
    @ResponseBody
    public Result group(@RequestParam String group) {
        adminService.addGroup(group);
        return Result.success();
    }

    @Operation(summary = "创建后台用户")
    @PostMapping("/admin/user")
    @ResponseBody
    public Result adminUser(@RequestBody AdminUserVO admin) {
        adminService.add(admin.getUsers());
        return Result.success();
    }

    @Operation(summary = "删除后台用户")
    @DeleteMapping("/admin/user/{username}")
    @ResponseBody
    public Result adminUserDel(@PathVariable("username") String username) {
        adminService.delUser(username);
        return Result.success();
    }

}
