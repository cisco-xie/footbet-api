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
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.model.UserConfig;
import com.example.demo.core.support.BaseController;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ConfigService;
import com.example.demo.api.FalaliApi;
import com.example.demo.core.result.Result;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.LoginDTO;
import com.example.demo.model.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "法拉利项目")
@RequestMapping("/api")
@RestController
public class BasicController extends BaseController {

    @Resource
    private RedissonClient redisson;

    @Resource
    private FalaliApi api;

    @Resource
    private ConfigService configService;

    @Operation(summary = "")
    @GetMapping("/add")
    public Result add(@RequestParam String username) {
        configService.add(username);
        return Result.success();
    }

    @Operation(summary = "")
    @GetMapping("/del")
    public Result del() {
        configService.del();
        return Result.success();
    }

    @Operation(summary = "获取平台用户是否自动下注")
    @GetMapping("/auto")
    public Result getAutoBet() {
        AdminLoginDTO admin = getUser();
        return Result.success(configService.getAutoBet(admin.getUsername()));
    }

    @Operation(summary = "设置平台用户是否自动下注")
    @PostMapping("/auto/{auto}")
    public Result autoBet(@PathVariable("auto") Integer auto) {
        AdminLoginDTO admin = getUser();
        configService.autoBet(admin.getUsername(), auto);
        return Result.success();
    }

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
        List<UserConfig> userList = configService.accounts(admin.getUsername(), null);
        // 异步更新账号余额并校验是否登录
        // ThreadUtil.execAsync(() -> {
        //     userList.forEach(userConfig -> {
        //         api.token(admin.getUsername(), userConfig.getId());
        //     });
        // });
        return Result.success(userList);
    }

    @Operation(summary = "用户配置")
    @PostMapping("/accounts")
    @ResponseBody
    public Result configAccount(@RequestBody @Validated ConfigUserVO userProxy) {
        AdminLoginDTO admin = getUser();
        String id = configService.editAccount(admin.getUsername(), userProxy);
//        if ("add".equalsIgnoreCase(userProxy.getOperationType())) {
//            // 新增成功后对盘口账号进行异步登录
//            ThreadUtil.execAsync(() -> {
//                api.singleLogin(admin.getUsername(), id);
//            });
//        }
        return Result.success();
    }

    @Operation(summary = "删除账号配置")
    @DeleteMapping("/accounts/{id}")
    public Result deleteAccount(@PathVariable("id") String id) {
        AdminLoginDTO admin = getUser();
        configService.deleteAccount(admin.getUsername(), id);
        return Result.success();
    }

    @Operation(summary = "一键启用方案配置")
    @GetMapping("/config/plan/enable")
    public Result configPlanEnable() {
        AdminLoginDTO admin = getUser();
        configService.plan(admin.getUsername(), 1);
        return Result.success();
    }

    @Operation(summary = "一键停用方案配置")
    @GetMapping("/config/plan/unenable")
    public Result configPlanUnenable() {
        AdminLoginDTO admin = getUser();
        configService.plan(admin.getUsername(), 0);
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
        return api.code("1d17d23f-50c1-45a1-a199-c1acf32865c8", null);
    }

    @Operation(summary = "一键登录")
    @GetMapping("/login")
    public Result login() {
        String uuid = IdUtil.randomUUID();
        String code = api.code(uuid, null);
        String params = "type=1&account=cs22222&password=WEwe2323&code=" + code;
        return Result.success(api.login(params, uuid));
    }

    @Operation(summary = "获取后台用户列表")
    @GetMapping("/admin/user")
    public Result adminUser() {
        return Result.success(configService.getUsers());
    }

    @Operation(summary = "获取后台用户小组列表")
    @GetMapping("/admin/group")
    @ResponseBody
    public Result group() {
        return Result.success(configService.getGroup());
    }
    @Operation(summary = "创建后台用户小组")
    @PostMapping("/admin/group")
    @ResponseBody
    public Result group(@RequestParam String group) {
        configService.addGroup(group);
        return Result.success();
    }

    @Operation(summary = "创建后台用户")
    @PostMapping("/admin/user")
    @ResponseBody
    public Result adminUser(@RequestBody AdminUserVO admin) {
        configService.add(admin.getUsers());
        return Result.success();
    }

    @Operation(summary = "删除后台用户")
    @DeleteMapping("/admin/user/{username}")
    @ResponseBody
    public Result adminUserDel(@PathVariable("username") String username) {
        configService.delUser(username);
        return Result.success();
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
    public Result batchLoginOut(@RequestParam String id) {
        AdminLoginDTO admin = getUser();
        api.singleLoginOut(admin.getUsername(), id);
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
    @GetMapping("/single-login")
    @ResponseBody
    public Result batchLogin(@RequestParam String id) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.singleLogin(admin.getUsername(), id));
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
    public Result token(@RequestParam String id) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.token(admin.getUsername(), id));
    }

    @Operation(summary = "获取账号余额", description = "此接口需轮询更新，如果data返回空则表示当前token已失效需重新获取")
    @GetMapping("/balance")
    public Result account(@RequestParam String id) {
        AdminLoginDTO admin = getUser();
        JSONArray jsonArray = api.account(admin.getUsername(), id);
        if (CollUtil.isNotEmpty(jsonArray)) {
            return Result.success();
        }
        return Result.failed(SystemError.USER_1002);
    }

    @Operation(summary = "获取期数")
    @GetMapping("/period")
    public Result period(@RequestParam String id, @RequestParam String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.period(admin.getUsername(), id, lottery)));
    }

    @Operation(summary = "获取比率")
    @GetMapping("/odds")
    public Result odds(@RequestParam String id, @RequestParam String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.odds(admin.getUsername(), id, lottery)));
    }

    @Operation(summary = "下注")
    @PostMapping("/bet")
    @ResponseBody
    public Result bet(@RequestParam String id, @RequestBody OrderVO order) {
        AdminLoginDTO admin = getUser();
        return Result.success(api.bet(admin.getUsername(), id, order));
    }

    @Operation(summary = "自动下注")
    @GetMapping("/auto/bet")
    @ResponseBody
    public Result autoBet() {
        api.autoBet();
        return Result.success();
    }

    @Operation(summary = "获取未结已结流水记录")
    @GetMapping("/settled")
    @ResponseBody
    public Result history(@RequestParam String id,
                          @RequestParam(value = "settled", required = false) Boolean settled,
                          @RequestParam(value = "pageNo", required = false) Integer pageNo) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.settled(admin.getUsername(), id, settled, pageNo, false)));
    }

    @Operation(summary = "获取两周流水记录")
    @GetMapping("/history")
    @ResponseBody
    public Result history(@RequestParam String id, @RequestParam String lottery) {
        AdminLoginDTO admin = getUser();
        return Result.success(JSONUtil.parseObj(api.history(admin.getUsername(), id, lottery)));
    }

    @Operation(summary = "异常投注")
    @GetMapping("/bet/failed")
    @ResponseBody
    public Result history() {
        AdminLoginDTO admin = getUser();
        return Result.success(configService.failedBet(admin.getUsername()));
    }

    @Operation(summary = "获取最新失败投注")
    @GetMapping("/bet/failed/latest")
    public Result latest() {
        AdminLoginDTO admin = getUser();
        return Result.success(configService.failedBetLatest(admin.getUsername()));
    }

    @Operation(summary = "确认最新失败投注")
    @GetMapping("/bet/failed/confirm")
    public Result confirm(@RequestParam String key) {
        configService.failedBetLatestConfirm(key);
        return Result.success();
    }

}
