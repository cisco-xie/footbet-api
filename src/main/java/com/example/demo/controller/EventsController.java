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

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.HandicapApi;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Slf4j
@Tag(name = "赛事管理")
@RequestMapping("/api")
@RestController
public class EventsController extends BaseController {

    @Resource
    private HandicapApi handicapApi;

    @Operation(summary = "获取网站赛事列表")
    @GetMapping("/events")
    public Result getWebsites(@RequestParam String websiteId, @RequestParam Integer type) {
        AdminLoginDTO admin = getUser();
        // 获取赛事数据
        Object eventLive = handicapApi.eventList(admin.getUsername(), websiteId, type);
        JSONArray result = JSONUtil.parseArray(eventLive);
        return Result.success(result);
    }

    @Operation(summary = "获取网站赛事列表")
    @GetMapping("/events/{websiteId}")
    public Result getWebsites(@PathVariable String websiteId) {
        AdminLoginDTO admin = getUser();

        // 获取两类赛事数据
        Object eventLive = handicapApi.events(admin.getUsername(), websiteId, ZhiBoSchedulesType.LIVESCHEDULE.getId());
        Object eventToday = handicapApi.events(admin.getUsername(), websiteId, ZhiBoSchedulesType.TODAYSCHEDULE.getId());

        Map<String, JSONObject> mergedMap = new LinkedHashMap<>();

        // 合并 eventLive 和 eventToday，容错处理 null
        for (Object data : new Object[]{eventLive, eventToday}) {
            if (data == null) continue;

            JSONArray array;
            try {
                array = JSONUtil.parseArray(data);
            } catch (Exception e) {
                continue; // 数据格式不正确，跳过该数据源
            }

            for (Object obj : array) {
                JSONObject event = JSONUtil.parseObj(obj);
                String id = event.getStr("id");
                if (id == null) continue;

                // 如果 map 中已有该赛事，合并 events
                if (mergedMap.containsKey(id)) {
                    JSONArray existingEvents = mergedMap.get(id).getJSONArray("events");
                    JSONArray newEvents = event.getJSONArray("events");

                    if (newEvents != null) {
                        Set<String> existingKeys = existingEvents.stream()
                                .map(e -> {
                                    JSONObject jo = (JSONObject) e;
                                    return jo.getStr("id") + "|" + jo.getStr("name");
                                })
                                .collect(Collectors.toSet());

                        for (Object newEv : newEvents) {
                            JSONObject jo = JSONUtil.parseObj(newEv);
                            String key = jo.getStr("id") + "|" + jo.getStr("name");
                            if (!existingKeys.contains(key)) {
                                existingEvents.add(jo);
                            }
                        }
                    }

                } else {
                    // 没有就直接添加进去
                    mergedMap.put(id, event);
                }
            }
        }

        JSONArray result = new JSONArray();
        result.addAll(mergedMap.values());
        return Result.success(result);
    }

}
