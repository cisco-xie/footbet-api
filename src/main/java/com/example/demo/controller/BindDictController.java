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
import com.example.demo.api.BindDictService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.WebSiteDTO;
import com.example.demo.model.dto.dict.BindLeagueDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.dict.BindLeagueVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "球队字典")
@RequestMapping("/api")
@RestController
public class BindDictController extends BaseController {

    @Resource
    private BindDictService bindDictService;

    @Operation(summary = "绑定字典")
    @PostMapping("/bind")
    public Result add(@RequestBody List<BindLeagueVO> bindLeagueVOS) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增网站
        bindDictService.bindDict(admin.getUsername(), bindLeagueVOS);
        return Result.success();
    }

    @Operation(summary = "删除绑定")
    @DeleteMapping("/bind/{websiteIdA}/{websiteIdB}")
    public Result delete(@PathVariable String websiteIdA, @PathVariable String websiteIdB) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法删除网站
        bindDictService.deleteBindDict(admin.getUsername(), websiteIdA, websiteIdB);
        return Result.success();
    }

    @Operation(summary = "获取绑定列表")
    @GetMapping("/bind")
    public Result<List<BindLeagueDTO>> getBindDict(@RequestParam String websiteIdA, @RequestParam String websiteIdB) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        List<BindLeagueVO> leagueVO = bindDictService.getBindDict(admin.getUsername(), websiteIdA, websiteIdB);
        return Result.success(BeanUtil.copyToList(leagueVO, BindLeagueDTO.class));
    }

}
