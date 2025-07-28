package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.ZhiBoOddsFormatType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.common.enmu.ZhiBoSportsType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 智博网站 - 账户额度 API具体实现
 */
@Component
public class WebsiteZhiBoEventsHandler {
//
//    private final WebsiteService websiteService;
//    private final ApiUrlService apiUrlService;
//
//    @Autowired
//    public WebsiteZhiBoEventsHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
//        this.websiteService = websiteService;
//        this.apiUrlService = apiUrlService;
//    }
//
//    @Override
//    public HttpEntity<String> buildRequest(JSONObject params) {
//        // 构造请求头
//        HttpHeaders headers = new HttpHeaders();
//        headers.add("accept", "*/*");
//        headers.add("content-type", "application/json");
//        headers.add("locale", "zh_CN");
//        headers.add("authorization", params.getStr("token"));
//
//        return new HttpEntity<>(headers);
//    }
//
//    @Override
//    public JSONObject parseResponse(JSONObject params, HttpResponse response) {
//
//        // 检查响应状态
//        if (response.getStatus() != 200) {
//            JSONObject res = new JSONObject();
//            res.putOpt("success", false);
//            if (response.getStatus() == 403) {
//                res.putOpt("code", 403);
//                res.putOpt("msg", "账户登录失效");
//                return res;
//            }
//            res.putOpt("msg", "获取赛事失败");
//            return res;
//        }
//        // 解析响应
//        JSONArray result = new JSONArray();
//        JSONObject responseJson = new JSONObject(response.body());
//        JSONArray leagues = responseJson.getJSONObject("schedule").getJSONArray("leagues");
//        if (!leagues.isEmpty()) {
//            leagues.forEach(league -> {
//                JSONObject leagueJson = new JSONObject();
//                JSONObject leagueJsonOld = (JSONObject) league;
//                leagueJson.putOpt("id", leagueJsonOld.getStr("id"));
//                leagueJson.putOpt("league", leagueJsonOld.getStr("name"));
//                JSONArray leaguesArray = new JSONArray();
//                leagueJsonOld.getJSONArray("events").forEach(event -> {
//                    JSONObject homeTeam = new JSONObject();
//                    JSONObject awayTeam = new JSONObject();
//                    JSONObject eventJsonOld = (JSONObject) event;
//                    homeTeam.putOpt("id", eventJsonOld.getStr("id"));
//                    homeTeam.putOpt("name", eventJsonOld.getStr("homeTeam"));
//                    homeTeam.putOpt("isHome", true);
//                    awayTeam.putOpt("id", eventJsonOld.getStr("id"));
//                    awayTeam.putOpt("name", eventJsonOld.getStr("awayTeam"));
//                    awayTeam.putOpt("isHome", false);
//                    leaguesArray.put(homeTeam);
//                    leaguesArray.put(awayTeam);
//                });
//                leagueJson.putOpt("events", leaguesArray);
//                result.put(leagueJson);
//            });
//        }
//        responseJson.putOpt("success", true);
//        responseJson.putOpt("leagues", result);
//        responseJson.putOpt("msg", "获取赛事成功");
//        return responseJson;
//    }
//
//    /**
//     * 发送账户额度请求
//     * uri          /data/events/{sportId}/{scheduleId}/{leagueId}/{oddsFormatId}/{oddsGroupId}
//     * leagueId     要检索价格表的联赛Id，默认所有联赛为0
//     * oddsGroupId  赔率组Id。可使用/member/info接口响应中的值，先直接写死3
//     * @param params 请求参数
//     * @return 结果
//     */
//    @Override
//    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
//
//        // 获取 完整API 路径
//        String username = params.getStr("adminUsername");
//        String siteId = params.getStr("websiteId");
//        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
//        String apiUrl = apiUrlService.getApiUrl(siteId, "events");
//        // 默认leagueId为0表示查询所有联赛
//        int leagueId = 0;
//        if (params.containsKey("leagueId")) {
//            leagueId = params.getInt("leagueId");
//        }
//        apiUrl = String.format(apiUrl, ZhiBoSportsType.SOCCER.getId(), params.getInt("showType"), leagueId, params.getInt("oddsFormatType"), 3);
//        // 构建请求
//        HttpEntity<String> requestBody = buildRequest(params);
//
//        // 构造请求体
//        String queryParams = String.format("_=%s",
//                System.currentTimeMillis()
//        );
//
//        // 拼接完整的 URL
//        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);
//
//        // 发送请求
//        HttpResponse response = null;
//        HttpRequest request = HttpRequest.get(fullUrl)
//                .addHeaders(requestBody.getHeaders().toSingleValueMap());
//        // 引入配置代理
//        HttpProxyConfig.configureProxy(request, userConfig);
//        response = request.execute();
//
//        // 解析响应
//        return parseResponse(params, response);
//    }
}
