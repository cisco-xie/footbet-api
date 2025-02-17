package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.factory.ApiHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 新2网站 - 赛事详情赔率 API具体实现
 */
@Component
public class WebsiteXinBaoEventsOddsHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventsOddsHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    // 版本
    private static final String VER = "2025-02-14-rmBanner_76";

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

        String showType = "live";  // 滚球赛事
//        String showType = "today";  // 今日赛事

        // 构造请求体
        String requestBody = String.format("p=get_game_more&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&ltype=3&isRB=Y&lid=%s&specialClick=&mode=NORMAL&filter=Main&ecid=%s&ts=%s",
                params.getStr("uid"),
                VER,
                showType,
                params.getStr("lid"),
                params.getStr("ecid"),
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
    public JSONObject parseResponse(HttpResponse response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        JSONObject responseJson = new JSONObject(response.body());
        System.out.println(responseJson);
        if (!responseJson.getJSONObject("serverresponse").getStr("code").equals("617")) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "获取赔率详情失败");
            return res;
        }
        if (ObjectUtil.isEmpty(responseJson.getJSONObject("serverresponse").getJSONArray("game"))) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取赔率详情为空");
            return responseJson;
        }
        JSONArray games = JSONUtil.parseArray(responseJson.getJSONObject("serverresponse").getJSONArray("game"));
        // 结果存储，用于合并相同的 lid
        String lid = games.getJSONObject(0).getStr("lid");                          // 联赛ID
        String ecid = responseJson.getJSONObject("serverresponse").getStr("ecid");  // 联赛ID
        String league = games.getJSONObject(0).getStr("league");                    // 联赛名称
        JSONArray result = new JSONArray();
        JSONObject leagueJson = new JSONObject();
        leagueJson.putOpt("id", lid);
        leagueJson.putOpt("ecid", ecid);
        leagueJson.putOpt("name", league);
        // 初始化比赛事件列表
        JSONArray events = new JSONArray();

        // 主队数据
        JSONObject homeTeam = new JSONObject();
        homeTeam.putOpt("id", games.getJSONObject(0).getStr("gnum_h")); // 主队ID
        homeTeam.putOpt("name", games.getJSONObject(0).getStr("team_h")); // 主队名称

        // 客队数据
        JSONObject awayTeam = new JSONObject();
        awayTeam.putOpt("id", games.getJSONObject(0).getStr("gnum_c")); // 客队ID
        awayTeam.putOpt("name", games.getJSONObject(0).getStr("team_c")); // 客队名称

        // 处理全场数据
        JSONObject homeFullCourt = new JSONObject();
        JSONObject awayFullCourt = new JSONObject();
        JSONObject homeFirstHalf = new JSONObject();
        JSONObject awayFirstHalf = new JSONObject();

        JSONObject homeLetBall = new JSONObject();
        JSONObject awayLetBall = new JSONObject();

        JSONObject homeOverSize = new JSONObject();
        JSONObject awayOverSize = new JSONObject();

        JSONObject firstHalfHomeLetBall = new JSONObject();
        JSONObject firstHalfAwayLetBall = new JSONObject();
        JSONObject homeFirstHomeOverSize = new JSONObject();
        JSONObject homeFirstAwayOverSize = new JSONObject();
        // 遍历所有比赛
        games.forEach(gameObj -> {
            JSONObject game = (JSONObject) gameObj;

            // 全场让球
            if (0 != game.getInt("ior_REH")) {
                homeLetBall.putOpt(getHandicapRange(game.getStr("ratio_re")), calculateOddsValue(game.getDouble("ior_REH")));
                awayLetBall.putOpt(getHandicapRange(game.getStr("ratio_re")), calculateOddsValue(game.getDouble("ior_REC")));
            }

            // 全场大小
            if (0 != game.getInt("ior_ROUH")) {
                homeOverSize.putOpt(getHandicapRange(game.getStr("ratio_rouo")), calculateOddsValue(game.getDouble("ior_ROUH")));
                awayOverSize.putOpt(getHandicapRange(game.getStr("ratio_rouu")), calculateOddsValue(game.getDouble("ior_ROUC")));
            }

            // 全场胜平负
            if (0 != game.getInt("ior_RMH")) {
                homeFullCourt.putOpt("win", game.getStr("ior_RMH"));
                homeFullCourt.putOpt("draw", game.getStr("ior_RMN"));
                awayFullCourt.putOpt("win", game.getStr("ior_RMC"));
                awayFullCourt.putOpt("draw", game.getStr("ior_RMN"));
            }

            // 半场让球
            if (0 != game.getInt("ior_HREH")) {
                firstHalfHomeLetBall.putOpt(getHandicapRange(game.getStr("ratio_hre")), calculateOddsValue(game.getDouble("ior_HREH")));
                firstHalfAwayLetBall.putOpt(getHandicapRange(game.getStr("ratio_hre")), calculateOddsValue(game.getDouble("ior_HREC")));
                homeFirstHalf.putOpt("letBall", firstHalfHomeLetBall);
                awayFirstHalf.putOpt("letBall", firstHalfAwayLetBall);
            }

            // 半场大小
            if (0 != game.getInt("ior_HROUH")) {
                homeFirstHomeOverSize.putOpt(getHandicapRange(game.getStr("ratio_hrouo")), calculateOddsValue(game.getDouble("ior_HROUH")));
                homeFirstAwayOverSize.putOpt(getHandicapRange(game.getStr("ratio_hrouu")), calculateOddsValue(game.getDouble("ior_HROUC")));
                homeFirstHalf.putOpt("overSize", homeFirstHomeOverSize);
                awayFirstHalf.putOpt("overSize", homeFirstAwayOverSize);
            }

            // 半场胜平负
            if (0 != game.getInt("ior_HRMH")) {
                homeFirstHalf.putOpt("win", game.getStr("ior_HRMH"));
                homeFirstHalf.putOpt("draw", game.getStr("ior_HRMN"));
                awayFirstHalf.putOpt("win", game.getStr("ior_HRMC"));
                awayFirstHalf.putOpt("draw", game.getStr("ior_HRMN"));
            }

        });

        homeFullCourt.putOpt("letBall", homeLetBall);
        awayFullCourt.putOpt("letBall", awayLetBall);

        homeFullCourt.putOpt("overSize", homeOverSize);
        awayFullCourt.putOpt("overSize", awayOverSize);

        // 组装主队和客队数据
        homeTeam.putOpt("fullCourt", homeFullCourt);
        homeTeam.putOpt("firstHalf", homeFirstHalf);
        awayTeam.putOpt("fullCourt", awayFullCourt);
        awayTeam.putOpt("firstHalf", awayFirstHalf);

        // 添加到事件列表
        events.add(homeTeam);
        events.add(awayTeam);
        leagueJson.putOpt("events", events);
        // 将事件列表添加到结果中
        result.add(leagueJson);

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
    public JSONObject execute(JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "events");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = HttpRequest.post(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应并返回
        return parseResponse(response);
    }

    /**
     * 计算handicap
     * @param handicap
     * @return
     */
    public String getHandicapRange(String handicap) {
        return handicap.replaceAll(" / ", "-");
    }

    /**
     * 数值计算优化
     * 新2网站的马来盘，返回的赔率需要处理一下，大于1的赔率需要减2才是正确的赔率
     */

    private String calculateOddsValue(double value) {
        return value > 1.0 ?
                String.format("%.3f", value - 2.0) :
                String.format("%.3f", value);
    }

    private void buildMarketData(JSONObject homeTeam, JSONObject awayTeam, JSONObject game) {
        // 全场数据处理
        JSONObject homeFull = new JSONObject();
        JSONObject awayFull = new JSONObject();

        // 让球盘
        processHandicap(homeFull, awayFull, game,
                "sw_ROUH", "ratio_rouho",
                "ior_ROUHO", "ior_ROUHU");

        // 大小盘
        processOverUnder(homeFull, awayFull, game,
                "sw_RE", "ratio_re",
                "ior_REH", "ior_REC");

        // 胜平负
        processWinDraw(homeFull, awayFull, game,
                "sw_RWM", "ior_RWMH1", "ior_RWMN", "ior_RWMC1");

        // 半场数据处理
        JSONObject homeHalf = new JSONObject();
        JSONObject awayHalf = new JSONObject();

        // 半场让球
        processHandicap(homeHalf, awayHalf, game,
                "sw_HRUH", "ratio_hruho",
                "ior_HRUHO", "ior_HRUHU");

        // 半场大小
        processOverUnder(homeHalf, awayHalf, game,
                "sw_HRE", "ratio_hre",
                "ior_HREH", "ior_HREC");

        // 半场胜平负
        processWinDraw(homeHalf, awayHalf, game,
                "sw_HRM", "ior_HRMH", "ior_HRMN", "ior_HRMC");

        homeTeam.putOpt("fullCourt", homeFull);
        homeTeam.putOpt("firstHalf", homeHalf);
        awayTeam.putOpt("fullCourt", awayFull);
        awayTeam.putOpt("firstHalf", awayHalf);
    }

    private void processHandicap(JSONObject home, JSONObject away, JSONObject game,
                                 String switchKey, String ratioKey,
                                 String homeOddsKey, String awayOddsKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            JSONObject homeLet = new JSONObject();
            JSONObject awayLet = new JSONObject();

            // 解析盘口值（示例处理逻辑，根据实际数据结构调整）
            String[] ratios = game.getStr(ratioKey).split("/");
            for (String ratio : ratios) {
                String trimmedRatio = ratio.trim();
                homeLet.putOpt(trimmedRatio, game.getStr(homeOddsKey));
                awayLet.putOpt(trimmedRatio, game.getStr(awayOddsKey));
            }

            home.putOpt("letBall", homeLet);
            away.putOpt("letBall", awayLet);
        }
    }

    private void processOverUnder(JSONObject home, JSONObject away, JSONObject game,
                                  String switchKey, String ratioKey,
                                  String overOddsKey, String underOddsKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            JSONObject homeOver = new JSONObject();
            JSONObject awayOver = new JSONObject();

            // 解析大小盘盘口
            String[] ouRatios = game.getStr(ratioKey).split("/");
            for (String ratio : ouRatios) {
                String trimmedRatio = ratio.trim();
                homeOver.putOpt(trimmedRatio, game.getStr(overOddsKey));
                awayOver.putOpt(trimmedRatio, game.getStr(underOddsKey));
            }

            home.putOpt("overSize", homeOver);
            away.putOpt("overSize", awayOver);
        }
    }

    private void processWinDraw(JSONObject home, JSONObject away, JSONObject game,
                                String switchKey, String homeWinKey,
                                String drawKey, String awayWinKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            home.putOpt("win", game.getStr(homeWinKey));
            home.putOpt("draw", game.getStr(drawKey));
            away.putOpt("win", game.getStr(awayWinKey));
            away.putOpt("draw", game.getStr(drawKey));
        }
    }
    
}
