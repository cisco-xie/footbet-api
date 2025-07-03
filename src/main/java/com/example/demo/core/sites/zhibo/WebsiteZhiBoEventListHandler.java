package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.common.enmu.ZhiBoSportsType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 智博网站 - 赛事列表-带赔率 API具体实现 用于操作网站查看赛事列表
 */
@Component
public class WebsiteZhiBoEventListHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteZhiBoEventListHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    @Override
    public HttpEntity<String> buildRequest(JSONObject params) {
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/json");
        headers.add("locale", "zh_CN");
        headers.add("authorization", params.getStr("token"));

        return new HttpEntity<>(headers);
    }

    @Override
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {

        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("code", response.getStatus());
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("msg", "获取赛事失败");
            return res;
        }
        String username = params.getStr("adminUsername");
        // 解析响应
        JSONArray result = new JSONArray();
        JSONObject responseJson = new JSONObject(response.body());
        JSONArray leagues = responseJson.getJSONObject("schedule").getJSONArray("leagues");
        if (!leagues.isEmpty()) {
            leagues.forEach(league -> {
                JSONObject leagueJsonOld = (JSONObject) league;
                leagueJsonOld.getJSONArray("events").forEach(event -> {
                    JSONObject eventJsonOld = (JSONObject) event;
                    JSONArray markets = eventJsonOld.getJSONArray("markets");

                    markets.forEach(market -> {
                        JSONObject marketJson = (JSONObject) market;
                        int marketGroupId = marketJson.getInt("marketGroupId");
                        // 半场判断逻辑（根据市场名称）
                        boolean isFirstHalf = marketJson.getStr("name").contains("半场");

                        String session = "";
                        String period = eventJsonOld.getStr("period");
                        int reTime = 0;
                        if (StringUtils.isNotBlank(period)) {
                            if (period.contains("HT")) {
                                // 中场休息
                                session = "HT";
                            } else if (period.contains("1H")) {
                                // 上半场
                                session = "1H";
                                // 提取 “1H ” 之后的部分并去掉 HTML 标签
                                String cleaned = period.replaceAll(".*?1H", "").replaceAll("<[^>]+>", "").replaceAll("'", "").trim(); // 得到 "45+2"
                                String minute = cleaned.split("\\+")[0].trim(); // 取 "+" 前的部分
                                reTime = Integer.parseInt(minute); // 得到分钟
                            } else if (period.contains("2H")) {
                                // 下半场（即全场）
                                session = "2H";
                                String cleaned = period.replaceAll(".*?2H", "").replaceAll("<[^>]+>", "").replaceAll("'", "").trim(); // 得到 "45+2"
                                String minute = cleaned.split("\\+")[0].trim(); // 取 "+" 前的部分
                                reTime = Integer.parseInt(minute); // 得到分钟
                            }
                        }

                        if (marketGroupId == 2) { // 让球盘
                            processHandicapMarket(username, leagueJsonOld, eventJsonOld, marketJson, isFirstHalf, "Home",
                                    session, reTime, result);
                        } else if (marketGroupId == 3) { // 大小盘
                            processHandicapMarket(username, leagueJsonOld, eventJsonOld, marketJson, isFirstHalf, "Over",
                                    session, reTime, result);
                        } else if (marketGroupId == 1) { // 胜平负盘 todo 赛事列表先不显示平负盘
                            /*processWinDrawWinMarket(username, marketJson, isFirstHalf,
                                    winHomeJson, drawAwayJson, winAwayJson,
                                    firstHalfWinHomeJson, firstHalfDrawAwayJson, firstHalfWinAwayJson);*/
                        }
                    });

                });
            });
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("leagues", result);
        responseJson.putOpt("msg", "获取赛事成功");
        return responseJson;
    }

    /**
     * 发送赛事列表请求
     * uri          /data/events/{sportId}/{scheduleId}/{leagueId}/{oddsFormatId}/{oddsGroupId}
     * leagueId     要检索价格表的联赛Id，默认所有联赛为0
     * oddsGroupId  赔率组Id。可使用/member/info接口响应中的值，先直接写死3
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
        // 默认leagueId为0表示查询所有联赛
        int leagueId = 0;
        if (params.containsKey("leagueId")) {
            leagueId = params.getInt("leagueId");
        }
        apiUrl = String.format(apiUrl, ZhiBoSportsType.SOCCER.getId(), params.getInt("showType"), leagueId, params.getInt("oddsFormatType"), 3);
        // 构建请求
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("_=%s",
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.get(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap())
                .timeout(10000);
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应
        return parseResponse(params, response);
    }

    /**
     * 计算handicap
     * @param handicap
     * @return
     */
    public String getHandicapRange(double handicap) {
        // 取 handicap 的绝对值，确保是正数
        handicap = Math.abs(handicap);
        // 判断 handicap 是否是 0.5 的倍数
        if (handicap % 0.5 == 0) {
            // 如果是 0.5 的倍数，直接返回原值
            return String.valueOf(handicap);
        } else {
            // 如果不是 0.5 的倍数，返回一个范围
            double lowerBound = handicap - 0.25;
            double upperBound = handicap + 0.25;
            return lowerBound + "-" + upperBound;
        }
    }

    // 辅助方法：处理让球盘和大小盘
    private void processHandicapMarket(String username, JSONObject leagueJson, JSONObject eventJsonOld, JSONObject marketJson, boolean isFirstHalf, String homeIndicator,
                                       String session, int reTime, JSONArray result) {
        JSONArray lines = marketJson.getJSONArray("lines");
        if (lines == null) return;

        AtomicReference<String> wallHome = new AtomicReference<>();
        AtomicReference<String> wallAway = new AtomicReference<>();

        String handicapType;
        if (2 == marketJson.getInt("marketGroupId")) {
            // 让球盘
            handicapType = "letBall";
        } else if (3 == marketJson.getInt("marketGroupId")) {
            // 大小盘
            handicapType = "overSize";
        } else {
            // 平负盘
            handicapType = "";
        }
        lines.forEach(line -> {
            /*String score = eventJsonOld.getStr("score");
            if (StringUtils.isNotBlank(score)) {
                score = score.replace("-", ":");
            };*/
            JSONObject leagueResult = new JSONObject();
            leagueResult.putOpt("id", leagueJson.getStr("id"));
            leagueResult.putOpt("league", leagueJson.getStr("name"));
            leagueResult.putOpt("type", isFirstHalf ? "firstHalf" : "fullCourt");             // 赛事类型
            leagueResult.putOpt("session", session);              // 当前阶段
            leagueResult.putOpt("handicapType", handicapType);      // 盘口类型
            leagueResult.putOpt("reTime", reTime);                // 时间
            leagueResult.putOpt("eventId", eventJsonOld.getStr("id"));
            leagueResult.putOpt("homeTeam", eventJsonOld.getStr("homeTeam"));
            leagueResult.putOpt("awayTeam", eventJsonOld.getStr("awayTeam"));
            leagueResult.putOpt("score", eventJsonOld.getStr("score"));               // 比分

            JSONObject lineJson = (JSONObject) line;
            lineJson.getJSONArray("marketSelections").forEach(selection -> {
                JSONObject sel = (JSONObject) selection;
                if (sel.containsKey("handicap")) {
                    double handicap = sel.getDouble("handicap");
                    String odds = sel.getStr("odds");
                    if (homeIndicator.equals(sel.getStr("indicator"))) {
                        // 主队
                        leagueResult.putOpt("homeBetId", sel.getStr("id"));                    // 投注id
                        leagueResult.putOpt("homeOdds", odds);
                        leagueResult.putOpt("homeDecimalOdds", sel.getStr("decimalOdds"));                // 投注id
                        leagueResult.putOpt("homeHandicap", handicap);
                        if (StringUtils.isBlank(wallHome.get())) {
                            if (handicap < 0) {
                                // 让球，上盘
                                wallHome.set("hanging");
                            } else if (handicap > 0) {
                                // 被让球，下盘
                                wallHome.set("foot");
                            }
                        }
                        leagueResult.putOpt("homeWall", wallHome.get());
                    } else {
                        // 客队
                        leagueResult.putOpt("awayBetId", sel.getStr("id"));                    // 投注id
                        leagueResult.putOpt("awayOdds", odds);
                        leagueResult.putOpt("awayDecimalOdds", sel.getStr("decimalOdds"));                // 投注id
                        leagueResult.putOpt("awayHandicap", handicap);
                        if (StringUtils.isBlank(wallAway.get())) {
                            if (handicap < 0) {
                                // 让球，上盘
                                wallAway.set("hanging");
                            } else if (handicap > 0) {
                                // 被让球，下盘
                                wallAway.set("foot");
                            }
                        }
                        leagueResult.putOpt("awayWall", wallAway.get());
                    }
                }
            });
            result.put(leagueResult);
        });
    }

    // 辅助方法：处理胜平负盘
    private void processWinDrawWinMarket(String username, JSONObject marketJson, boolean isFirstHalf,
                                         JSONObject fullHome, JSONObject fullDraw, JSONObject fullAway,
                                         JSONObject halfHome, JSONObject halfDraw, JSONObject halfAway) {
        JSONArray selections = marketJson.getJSONArray("selections");
        if (selections == null) return;

        selections.forEach(selection -> {
            JSONObject sel = (JSONObject) selection;
            String indicator = sel.getStr("indicator");
            String id = sel.getStr("id");
            String odds = sel.getStr("odds");
            String decimalOdds = sel.getStr("decimalOdds");
            // double handicap = sel.getDouble("handicap");
            if (isFirstHalf) {
                switch (indicator) {
                    case "Home":
                        fullHome.putOpt("id", id);                    // 投注id
                        fullHome.putOpt("odds", odds);
                        fullHome.putOpt("decimalOdds", decimalOdds);
                        // fullHome.putOpt("handicap", handicap);
                        break;
                    case "Draw":
                        fullDraw.putOpt("id", id);                    // 投注id
                        fullDraw.putOpt("odds", odds);
                        fullDraw.putOpt("decimalOdds", decimalOdds);
                        // fullDraw.putOpt("handicap", handicap);
                        break;
                    case "Away":
                        fullAway.putOpt("id", id);                    // 投注id
                        fullAway.putOpt("odds", odds);
                        fullAway.putOpt("decimalOdds", decimalOdds);
                        // fullAway.putOpt("handicap", handicap);
                        break;
                }
            } else {
                switch (indicator) {
                    case "Home":
                        halfHome.putOpt("id", id);                    // 投注id
                        halfHome.putOpt("odds", odds);
                        halfHome.putOpt("decimalOdds", decimalOdds);
                        // fullHome.putOpt("handicap", handicap);
                        break;
                    case "Draw":
                        halfDraw.putOpt("id", id);                    // 投注id
                        halfDraw.putOpt("odds", odds);
                        halfDraw.putOpt("decimalOdds", decimalOdds);
                        // halfDraw.putOpt("handicap", handicap);
                        break;
                    case "Away":
                        halfAway.putOpt("id", id);                    // 投注id
                        halfAway.putOpt("odds", odds);
                        halfAway.putOpt("decimalOdds", decimalOdds);
                        // halfAway.putOpt("handicap", handicap);
                        break;
                }
            }
            // 记录赔率
            apiUrlService.updateOddsCache(username, sel.getStr("id"), sel.getDouble("odds"));
        });
    }

}
