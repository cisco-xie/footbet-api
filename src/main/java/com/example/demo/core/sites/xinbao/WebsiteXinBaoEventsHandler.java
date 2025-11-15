package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 账户额度 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoEventsHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventsHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.dispatcher = dispatcher;
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    /**
     * 构建请求头
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public Map<String, String> buildHeaders(JSONObject params) {
        // 构造请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/x-www-form-urlencoded");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("Connection", "keep-alive");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("User-Agent", Constants.USER_AGENT);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");

        return headers;
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public String buildRequest(JSONObject params) {
        String showType;  // 滚球赛事
        String rType;  // 滚球赛事
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            showType = "live";  // 滚球赛事
            rType = "rb";  // 滚球赛事
        } else {
            showType = "today";  // 今日赛事
            rType = "r";  // 今日赛事
        }
        // 构造请求体
        return String.format("p=get_game_list&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&rtype=%s&ltype=3&cupFantasy=N&sorttype=L&isFantasy=N&ts=%s",
                params.getStr("uid"),
                Constants.VER,
                showType,
                rType,
                System.currentTimeMillis()
        );
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        Document docResult = XmlUtil.readXML(response.getBody());
        JSONObject responseJson = new JSONObject(response.getBody());
        Object original = XmlUtil.getByXPath("//serverresponse/original", docResult, XPathConstants.STRING);
        if (ObjectUtil.isEmpty(original)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取账户赛事失败");
            return responseJson;
        }
        // 结果存储，用于合并相同的 lid
        Map<String, JSONObject> leagueMap = new HashMap<>();
        JSONArray result = new JSONArray();
        JSONObject originalJson = JSONUtil.parseObj(original);

        originalJson.entrySet().stream()
            .sorted(Comparator.comparing(Map.Entry::getKey)) // 按 key 升序
            .forEach(json -> {
                JSONObject gameJson = JSONUtil.parseObj(json.getValue());
                String lid = gameJson.getStr("LID");        // 联赛LID
                String gid = gameJson.getStr("GID");        // 比赛队伍GID
                String ecid = gameJson.getStr("ECID");      // 联赛ECID
                String league = gameJson.getStr("LEAGUE");  // 联赛名称
                String PTYPE = gameJson.getStr("PTYPE");   // 队名别称类型，如果有则不显示此队伍
                if (StringUtils.isNotBlank(PTYPE)) {
                    return;
                }
                // 查找是否已经存在相同的 lid（赛事）
                JSONObject leagueJson = leagueMap.get(lid);
                if (leagueJson == null) {
                    // 如果是新的联赛，创建一个新的 JSONObject
                    leagueJson = new JSONObject();
                    leagueJson.putOpt("id", lid);
                    leagueJson.putOpt("league", league);
                    leagueJson.putOpt("events", new JSONArray());
                    leagueMap.put(lid, leagueJson);
                }

                // 处理队伍信息
                JSONObject eventHJson = new JSONObject();
                // H是主队C是客队
                eventHJson.putOpt("id", ecid);
                eventHJson.putOpt("name", gameJson.getStr("TEAM_H") + " -vs- " + gameJson.getStr("TEAM_C"));

                // 将队伍信息添加到当前联赛的 events 中
                JSONArray events = leagueJson.getJSONArray("events");
                events.put(eventHJson);
            });
        // 将所有合并后的联赛放入 result 数组中
        result.addAll(leagueMap.values());

        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", result);
        responseJson.putOpt("msg", "获取账户赛事成功");
        return responseJson;
    }

    /**
     * 发送账户额度请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "events");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.executeFull("POST", fullUrl, requestBody, requestHeaders, userConfig);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }
}
