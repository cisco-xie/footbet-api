package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

                    // 让手盘赔率
                    JSONObject winHomeJson = new JSONObject();              // 主胜 - 全场
                    JSONObject winAwayJson = new JSONObject();              // 平 - 全场
                    JSONObject drawAwayJson = new JSONObject();             // 客胜 - 全场
                    JSONObject firstHalfWinHomeJson = new JSONObject();     // 主胜 - 半场
                    JSONObject firstHalfWinAwayJson = new JSONObject();     // 平 - 半场
                    JSONObject firstHalfDrawAwayJson = new JSONObject();    // 客胜 - 半场

                    markets.forEach(market -> {
                        JSONObject marketJson = (JSONObject) market;
                        int marketGroupId = marketJson.getInt("marketGroupId");
                        // 半场判断逻辑（根据市场名称）
                        boolean isFirstHalf = marketJson.getStr("name").contains("半场");

                        if (marketGroupId == 2) { // 让球盘
                            processHandicapMarket(username, marketJson, isFirstHalf, "Home",
                                    letHomeJson, letAwayJson,
                                    firstHalfLetHomeJson, firstHalfLetAwayJson);
                        } else if (marketGroupId == 3) { // 大小盘
                            processHandicapMarket(username, marketJson, isFirstHalf, "Over",
                                    overSizeJson, underSizeJson,
                                    firstHalfOverSizeJson, firstHalfUnderSizeJson);
                        } else if (marketGroupId == 1) { // 胜平负盘
                            processWinDrawWinMarket(username, marketJson, isFirstHalf,
                                    winHomeJson, drawAwayJson, winAwayJson,
                                    firstHalfWinHomeJson, firstHalfDrawAwayJson, firstHalfWinAwayJson);
                        }
                    });

                    // 将处理后的数据放入结果对象
                    fullHomeCourt.putOpt("letBall", letHomeJson)
                            .putOpt("overSize", overSizeJson)
                            .putOpt("win", winHomeJson)
                            .putOpt("draw", drawAwayJson);
                    firstHalfHomeCourt.putOpt("letBall", firstHalfLetHomeJson)
                            .putOpt("overSize", firstHalfOverSizeJson)
                            .putOpt("win", winAwayJson)
                            .putOpt("draw", drawAwayJson);
                    // 同理处理客队和underSize...
                    fullAwayCourt.putOpt("letBall", letAwayJson)
                            .putOpt("overSize", underSizeJson)
                            .putOpt("win", firstHalfWinHomeJson)
                            .putOpt("draw", firstHalfDrawAwayJson);
                    firstHalfAwayCourt.putOpt("letBall", firstHalfLetAwayJson)
                            .putOpt("overSize", firstHalfUnderSizeJson)
                            .putOpt("win", firstHalfWinAwayJson)
                            .putOpt("draw", firstHalfDrawAwayJson);

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
                            // 下半场时长需要加上上半场45分钟(固定加45分钟，不用管上半场有没有附加赛之类的)
                            reTime += 45;
                        }
                    }
                    homeTeam.putOpt("id", eventJsonOld.getStr("id"));
                    homeTeam.putOpt("name", homeTeamStr);
                    homeTeam.putOpt("isHome", true);
                    homeTeam.putOpt("session", session);
                    homeTeam.putOpt("reTime", reTime);
                    homeTeam.putOpt("score", eventJsonOld.getStr("score"));
                    homeTeam.putOpt("fullCourt", fullHomeCourt);
                    homeTeam.putOpt("firstHalf", firstHalfHomeCourt);

                    awayTeam.putOpt("id", eventJsonOld.getStr("id"));
                    awayTeam.putOpt("name", awayTeamStr);
                    awayTeam.putOpt("session", session);
                    awayTeam.putOpt("reTime", reTime);
                    awayTeam.putOpt("isHome", false);
                    awayTeam.putOpt("score", eventJsonOld.getStr("score"));
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
        apiUrl = String.format(apiUrl, ZhiBoSportsType.SOCCER.getId(), ZhiBoSchedulesType.LIVESCHEDULE.getId(), leagueId, params.getInt("oddsFormatType"), 3);
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
    private void processHandicapMarket(String username, JSONObject marketJson, boolean isFirstHalf, String homeIndicator,
                                       JSONObject fullHome, JSONObject fullAway,
                                       JSONObject firstHalfHome, JSONObject firstHalfAway) {
        JSONArray lines = marketJson.getJSONArray("lines");
        if (lines == null) return;

        JSONObject targetHome = isFirstHalf ? firstHalfHome : fullHome;
        JSONObject targetAway = isFirstHalf ? firstHalfAway : fullAway;

        AtomicReference<String> wallHome = new AtomicReference<>();
        AtomicReference<String> wallAway = new AtomicReference<>();

        lines.forEach(line -> {
            JSONObject lineJson = (JSONObject) line;
            lineJson.getJSONArray("marketSelections").forEach(selection -> {
                JSONObject sel = (JSONObject) selection;
                if (sel.containsKey("handicap")) {
                    double handicap = sel.getDouble("handicap");
                    String key = getHandicapRange(handicap);
                    String odds = sel.getStr("odds");
                    JSONObject oddsJson = new JSONObject();
                    oddsJson.putOpt("id", sel.getStr("id"));                    // 投注id
                    oddsJson.putOpt("odds", odds);
                    oddsJson.putOpt("decimalOdds", sel.getStr("decimalOdds"));                // 投注id
                    oddsJson.putOpt("handicap", handicap);
                    if (homeIndicator.equals(sel.getStr("indicator"))) {
                        if (StringUtils.isBlank(wallHome.get())) {
                            if (handicap < 0) {
                                // 让球，上盘
                                wallHome.set("hanging");
                            } else if (handicap > 0) {
                                // 被让球，下盘
                                wallHome.set("foot");
                            }
                        }
                        oddsJson.putOpt("wall", wallHome.get());
                        targetHome.putOpt(key, oddsJson);
                    } else {
                        if (StringUtils.isBlank(wallAway.get())) {
                            if (handicap < 0) {
                                // 让球，上盘
                                wallAway.set("hanging");
                            } else if (handicap > 0) {
                                // 被让球，下盘
                                wallAway.set("foot");
                            }
                        }
                        oddsJson.putOpt("wall", wallAway.get());
                        targetAway.putOpt(key, oddsJson);
                    }
                    // 记录赔率
                    apiUrlService.updateOddsCache(username, sel.getStr("id"), sel.getDouble("odds"));
                }
            });
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
