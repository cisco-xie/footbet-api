package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 账户额度 API具体实现
 */
@Component
public class WebsiteXinBaoEventsHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventsHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public HttpEntity<String> buildRequest(JSONObject params) {
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/x-www-form-urlencoded");

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
        String requestBody = String.format("p=get_game_list&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&rtype=%s&ltype=3&cupFantasy=N&sorttype=L&isFantasy=N&ts=%s",
                params.getStr("uid"),
                Constants.VER,
                showType,
                rType,
                System.currentTimeMillis()
        );
        return new HttpEntity<>(requestBody, headers);
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        Document docResult = XmlUtil.readXML(response.body());
        JSONObject responseJson = new JSONObject(response.body());
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

        originalJson.forEach(json -> {
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
            JSONObject eventCJson = new JSONObject();
            JSONObject eventHJson = new JSONObject();
            // H是主队C是客队
            // eventHJson.putOpt("id", gameJson.getStr("GNUM_H"));
            eventHJson.putOpt("id", gid);
            eventHJson.putOpt("name", gameJson.getStr("TEAM_H"));
            eventHJson.putOpt("isHome", true);
            eventHJson.putOpt("ecid", ecid);
            // eventCJson.putOpt("id", gameJson.getStr("GNUM_C"));
            eventCJson.putOpt("id", gid);
            eventCJson.putOpt("name", gameJson.getStr("TEAM_C"));
            eventCJson.putOpt("isHome", false);
            eventCJson.putOpt("ecid", ecid);

            // 将队伍信息添加到当前联赛的 events 中
            JSONArray events = leagueJson.getJSONArray("events");
            events.put(eventHJson);
            events.put(eventCJson);
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
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.post(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap())
                .body(requestBody.getBody());
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应并返回
        return parseResponse(params, response);
    }
}
