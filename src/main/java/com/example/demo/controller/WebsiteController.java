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
import com.example.demo.core.support.BaseController;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.result.Result;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.dto.WebSiteDTO;
import com.example.demo.model.vo.WebsiteVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "网站管理")
@RequestMapping("/api")
@RestController
public class WebsiteController extends BaseController {

    @Resource
    private WebsiteService websiteService;

    @Operation(summary = "新增网站")
    @PostMapping("/websites")
    public Result add(@RequestBody WebsiteVO webSiteVO) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法新增网站
        websiteService.saveWebsite(admin.getUsername(), webSiteVO);
        return Result.success();
    }

    @Operation(summary = "删除网站")
    @DeleteMapping("/websites/{id}")
    public Result delete(@PathVariable String id) {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法删除网站
        websiteService.deleteWebsite(admin.getUsername(), id);
        return Result.success();
    }

    @Operation(summary = "获取用户网站列表")
    @GetMapping("/websites")
    public Result<List<WebSiteDTO>> getWebsites() {
        AdminLoginDTO admin = getUser();
        // 调用服务层方法获取网站列表
        List<WebsiteVO> websites = websiteService.getWebsites(admin.getUsername());
        return Result.success(BeanUtil.copyToList(websites, WebSiteDTO.class));
    }

}
