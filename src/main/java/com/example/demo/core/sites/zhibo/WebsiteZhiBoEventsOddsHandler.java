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
import com.example.demo.core.factory.ApiHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 智博网站 - 赛事列表-带赔率 API具体实现
 */
@Component
public class WebsiteZhiBoEventsOddsHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteZhiBoEventsOddsHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
    public JSONObject parseResponse(HttpResponse response) {

        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("msg", "获取赛事失败");
            return res;
        }
        // 解析响应
        JSONArray result = new JSONArray();
        JSONObject responseJson = new JSONObject(response.body());
        JSONArray leagues = responseJson.getJSONObject("schedule").getJSONArray("leagues");
        System.out.println(leagues);
        if (!leagues.isEmpty()) {
            leagues.forEach(league -> {
                JSONObject leagueJson = new JSONObject();
                JSONObject leagueJsonOld = (JSONObject) league;
                leagueJson.putOpt("id", leagueJsonOld.getStr("id"));
                leagueJson.putOpt("league", leagueJsonOld.getStr("name"));
                JSONArray leaguesArray = new JSONArray();
                leagueJsonOld.getJSONArray("events").forEach(event -> {
                    JSONObject homeTeam = new JSONObject();
                    JSONObject awayTeam = new JSONObject();
                    // 全场
                    JSONObject fullHomeCourt = new JSONObject();
                    JSONObject fullAwayCourt = new JSONObject();
                    // 上半场
                    JSONObject firstHalfHomeCourt = new JSONObject();
                    JSONObject firstHalfAwayCourt = new JSONObject();
                    JSONObject eventJsonOld = (JSONObject) event;
                    String homeTeamStr = eventJsonOld.getStr("homeTeam");
                    String awayTeamStr = eventJsonOld.getStr("awayTeam");
                    JSONArray markets = eventJsonOld.getJSONArray("markets");

                    // 让球盘赔率
                    JSONObject letHomeJson = new JSONObject();
                    JSONObject letAwayJson = new JSONObject();
                    JSONObject firstHalfLetHomeJson = new JSONObject();
                    JSONObject firstHalfLetAwayJson = new JSONObject();
                    // 大小盘赔率
                    JSONObject overSizeJson = new JSONObject();
                    JSONObject underSizeJson = new JSONObject();
                    JSONObject firstHalfOverSizeJson = new JSONObject();
                    JSONObject firstHalfUnderSizeJson = new JSONObject();
                    // 胜平负盘赔率（让手盘）
                    AtomicReference<String> winHome = new AtomicReference<>("");            // 主胜 - 全场
                    AtomicReference<String> draw = new AtomicReference<>("");               // 平 - 全场
                    AtomicReference<String> winAway = new AtomicReference<>("");            // 客胜 - 全场
                    AtomicReference<String> firstHalfWinHome = new AtomicReference<>("");   // 主胜 - 半场
                    AtomicReference<String> firstHalfDraw = new AtomicReference<>("");      // 平 - 半场
                    AtomicReference<String> firstHalfWinAway = new AtomicReference<>("");   // 客胜 - 半场

                    markets.forEach(market -> {
                        JSONObject marketJson = (JSONObject) market;
                        int marketGroupId = marketJson.getInt("marketGroupId");
                        // 半场判断逻辑（根据市场名称）
                        boolean isFirstHalf = marketJson.getStr("name").contains("半场");

                        if (marketGroupId == 2) { // 让球盘
                            processHandicapMarket(marketJson, isFirstHalf, "Home",
                                    letHomeJson, letAwayJson,
                                    firstHalfLetHomeJson, firstHalfLetAwayJson);
                        } else if (marketGroupId == 3) { // 大小盘
                            processHandicapMarket(marketJson, isFirstHalf, "Over",
                                    overSizeJson, underSizeJson,
                                    firstHalfOverSizeJson, firstHalfUnderSizeJson);
                        } else if (marketGroupId == 1) { // 胜平负盘
                            processWinDrawWinMarket(marketJson, isFirstHalf,
                                    winHome, draw, winAway,
                                    firstHalfWinHome, firstHalfDraw, firstHalfWinAway);
                        }
                    });

                    // 将处理后的数据放入结果对象
                    fullHomeCourt.putOpt("letBall", letHomeJson)
                            .putOpt("overSize", overSizeJson)
                            .putOpt("win", winHome.get())
                            .putOpt("draw", draw.get());
                    firstHalfHomeCourt.putOpt("letBall", firstHalfLetHomeJson)
                            .putOpt("overSize", firstHalfOverSizeJson)
                            .putOpt("win", firstHalfWinHome.get())
                            .putOpt("draw", firstHalfDraw.get());
                    // 同理处理客队和underSize...
                    fullAwayCourt.putOpt("letBall", letAwayJson)
                            .putOpt("overSize", underSizeJson)
                            .putOpt("win", winAway.get())
                            .putOpt("draw", draw.get());
                    firstHalfAwayCourt.putOpt("letBall", firstHalfLetAwayJson)
                            .putOpt("overSize", firstHalfUnderSizeJson)
                            .putOpt("win", firstHalfWinAway.get())
                            .putOpt("draw", firstHalfDraw.get());

                    homeTeam.putOpt("id", eventJsonOld.getStr("id"));
                    homeTeam.putOpt("name", homeTeamStr);
                    homeTeam.putOpt("fullCourt", fullHomeCourt);
                    homeTeam.putOpt("firstHalf", firstHalfHomeCourt);

                    awayTeam.putOpt("id", eventJsonOld.getStr("id"));
                    awayTeam.putOpt("name", awayTeamStr);
                    awayTeam.putOpt("fullCourt", fullAwayCourt);
                    awayTeam.putOpt("firstHalf", firstHalfAwayCourt);
                    leaguesArray.put(homeTeam);
                    leaguesArray.put(awayTeam);
                });
                leagueJson.putOpt("events", leaguesArray);
                result.put(leagueJson);
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
    public JSONObject execute(JSONObject params) {

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
        apiUrl = String.format(apiUrl, ZhiBoSportsType.SOCCER.getId(), ZhiBoSchedulesType.LIVESCHEDULE.getId(), leagueId, ZhiBoOddsFormatType.RM.getId(), 3);
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("_=%s",
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = HttpRequest.get(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .execute();

        // 解析响应
        return parseResponse(response);
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
    private void processHandicapMarket(JSONObject marketJson, boolean isFirstHalf, String homeIndicator,
                                       JSONObject fullHome, JSONObject fullAway,
                                       JSONObject firstHalfHome, JSONObject firstHalfAway) {
        JSONArray lines = marketJson.getJSONArray("lines");
        if (lines == null) return;

        JSONObject targetHome = isFirstHalf ? firstHalfHome : fullHome;
        JSONObject targetAway = isFirstHalf ? firstHalfAway : fullAway;

        lines.forEach(line -> {
            JSONObject lineJson = (JSONObject) line;
            lineJson.getJSONArray("marketSelections").forEach(selection -> {
                JSONObject sel = (JSONObject) selection;
                if (sel.containsKey("handicap")) {
                    double handicap = sel.getDouble("handicap");
                    String key = getHandicapRange(handicap);
                    String odds = sel.getStr("odds");
                    if (homeIndicator.equals(sel.getStr("indicator"))) {
                        targetHome.putOpt(key, odds);
                    } else {
                        targetAway.putOpt(key, odds);
                    }
                }
            });
        });
    }

    // 辅助方法：处理胜平负盘
    private void processWinDrawWinMarket(JSONObject marketJson, boolean isFirstHalf,
                                         AtomicReference<String> fullHome, AtomicReference<String> fullDraw, AtomicReference<String> fullAway,
                                         AtomicReference<String> halfHome, AtomicReference<String> halfDraw, AtomicReference<String> halfAway) {
        JSONArray selections = marketJson.getJSONArray("selections");
        if (selections == null) return;

        selections.forEach(selection -> {
            JSONObject sel = (JSONObject) selection;
            String indicator = sel.getStr("indicator");
            String odds = sel.getStr("odds");
            if (isFirstHalf) {
                switch (indicator) {
                    case "Home": halfHome.set(odds); break;
                    case "Draw": halfDraw.set(odds); break;
                    case "Away": halfAway.set(odds); break;
                }
            } else {
                switch (indicator) {
                    case "Home": fullHome.set(odds); break;
                    case "Draw": fullDraw.set(odds); break;
                    case "Away": fullAway.set(odds); break;
                }
            }
        });
    }

}
