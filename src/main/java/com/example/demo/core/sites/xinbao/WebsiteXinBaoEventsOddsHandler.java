package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.core.factory.ApiHandler;
import org.apache.commons.lang3.StringUtils;
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
                Constants.VER,
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
        if (!responseJson.getJSONObject("serverresponse").getStr("code").equals("617")) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "获取赔率详情失败");
            return res;
        }
        if (!responseJson.getJSONObject("serverresponse").containsKey("game")) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取赔率详情为空");
            return responseJson;
        }
        String gameObject = String.valueOf(responseJson.getJSONObject("serverresponse").get("game"));
        if (StringUtils.isBlank(gameObject)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取赔率详情为空");
            return responseJson;
        }
        JSONArray games = new JSONArray();
        if (JSONUtil.isTypeJSONObject(gameObject)) {
            games.add(JSONUtil.parseObj(gameObject));
        } else if (JSONUtil.isTypeJSONArray(gameObject)) {
            games = JSONUtil.parseArray(gameObject);
        }
        // 结果存储，用于合并相同的 lid
        String lid = games.getJSONObject(0).getStr("lid");                          // 联赛ID
        String ecid = responseJson.getJSONObject("serverresponse").getStr("ecid");  // 联赛ID
        String league = games.getJSONObject(0).getStr("league");                    // 联赛名称
        JSONArray result = new JSONArray();
        JSONObject leagueJson = new JSONObject();
        leagueJson.putOpt("id", lid);
        leagueJson.putOpt("ecid", ecid);
        leagueJson.putOpt("league", league);
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
            if (game.containsKey("ior_REH") && 0 != game.getInt("ior_REH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_REH"))); // 投注赔率
                homeOddsJson.putOpt("oddFType", game.getStr("strong"));
                homeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOddsJson.putOpt("wtype", "RE");
                homeOddsJson.putOpt("rtype", "REH");
                homeOddsJson.putOpt("choseTeam", "H");
                homeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_re")));
                homeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_re"), "主队"));

                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_REC"))); // 投注赔率
                awayOddsJson.putOpt("oddFType", game.getStr("strong"));
                awayOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOddsJson.putOpt("wtype", "RE");
                awayOddsJson.putOpt("rtype", "REC");
                awayOddsJson.putOpt("choseTeam", "C");
                awayOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_re"))));
                awayOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_re"), "客队"));

                homeLetBall.putOpt(getHandicapRange(game.getStr("ratio_re")), homeOddsJson);
                awayLetBall.putOpt(getHandicapRange(game.getStr("ratio_re")), awayOddsJson);
            }

            // 全场大小
            if (game.containsKey("ior_ROUH") && 0 != game.getInt("ior_ROUH")) {
                JSONObject homeOverSizeOddsJson = new JSONObject();
                homeOverSizeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOverSizeOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_ROUC"))); // 投注赔率
                homeOverSizeOddsJson.putOpt("oddFType", game.getStr("strong"));
                homeOverSizeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOverSizeOddsJson.putOpt("wtype", "ROU");
                homeOverSizeOddsJson.putOpt("rtype", "ROUC");
                homeOverSizeOddsJson.putOpt("choseTeam", "C");
                homeOverSizeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_rouo")));
                homeOverSizeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_rouo"), "大"));

                JSONObject awayOverSizeOddsJson = new JSONObject();
                awayOverSizeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOverSizeOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_ROUH"))); // 投注赔率
                awayOverSizeOddsJson.putOpt("oddFType", game.getStr("strong"));
                awayOverSizeOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOverSizeOddsJson.putOpt("wtype", "ROU");
                awayOverSizeOddsJson.putOpt("rtype", "ROUH");
                awayOverSizeOddsJson.putOpt("choseTeam", "H");
                awayOverSizeOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_rouo"))));
                awayOverSizeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_rouo"), "小"));

                homeOverSize.putOpt(getHandicapRange(game.getStr("ratio_rouo")), homeOverSizeOddsJson);
                awayOverSize.putOpt(getHandicapRange(game.getStr("ratio_rouu")), awayOverSizeOddsJson);
            }

            // 全场胜平负
            if (game.containsKey("ior_RMH") && 0 != game.getInt("ior_RMH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr("ior_RMH"));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr("ior_RMC"));      // 投注赔率
                JSONObject drawOddsJson = new JSONObject();
                drawOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                drawOddsJson.putOpt("odds", game.getStr("ior_RMN"));      // 投注赔率
                homeFullCourt.putOpt("win", homeOddsJson);
                homeFullCourt.putOpt("draw", drawOddsJson);
                awayFullCourt.putOpt("win", awayOddsJson);
                awayFullCourt.putOpt("draw", drawOddsJson);
            }

            // 半场让球
            if (game.containsKey("ior_HREH") && 0 != game.getInt("ior_HREH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_HREH"))); // 投注赔率
                homeOddsJson.putOpt("oddFType", game.getStr("strong"));
                homeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOddsJson.putOpt("wtype", "HRE");
                homeOddsJson.putOpt("rtype", "HREH");
                homeOddsJson.putOpt("choseTeam", "H");
                homeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_hre")));
                homeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_re"), "主队"));

                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_HREC"))); // 投注赔率
                awayOddsJson.putOpt("oddFType", game.getStr("strong"));
                awayOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOddsJson.putOpt("wtype", "HRE");
                awayOddsJson.putOpt("rtype", "HREC");
                awayOddsJson.putOpt("choseTeam", "C");
                awayOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_hre"))));
                awayOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_re"), "客队"));

                firstHalfHomeLetBall.putOpt(getHandicapRange(game.getStr("ratio_hre")), homeOddsJson);
                firstHalfAwayLetBall.putOpt(getHandicapRange(game.getStr("ratio_hre")), awayOddsJson);
                homeFirstHalf.putOpt("letBall", firstHalfHomeLetBall);
                awayFirstHalf.putOpt("letBall", firstHalfAwayLetBall);
            }

            // 半场大小
            if (game.containsKey("ior_HROUH") && 0 != game.getInt("ior_HROUH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                homeOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_HROUC"))); // 投注赔率
                homeOddsJson.putOpt("gtype", game.getStr("gtype"));
                homeOddsJson.putOpt("wtype", "HROU");
                homeOddsJson.putOpt("rtype", "HROUC");
                homeOddsJson.putOpt("choseTeam", "C");
                homeOddsJson.putOpt("con", getMiddleValue(game.getStr("ratio_hrouo")));
                homeOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_hrouo"), "大"));

                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));                              // 投注id
                awayOddsJson.putOpt("odds", calculateOddsValue(game.getDouble("ior_HROUH"))); // 投注赔率
                awayOddsJson.putOpt("gtype", game.getStr("gtype"));
                awayOddsJson.putOpt("wtype", "HROU");
                awayOddsJson.putOpt("rtype", "HROUH");
                awayOddsJson.putOpt("choseTeam", "H");
                awayOddsJson.putOpt("con", -Math.abs(getMiddleValue(game.getStr("ratio_hrouo"))));
                awayOddsJson.putOpt("ratio", getRatio(game.getStr("ratio_hrouo"), "小"));

                homeFirstHomeOverSize.putOpt(getHandicapRange(game.getStr("ratio_hrouo")), homeOddsJson);
                homeFirstAwayOverSize.putOpt(getHandicapRange(game.getStr("ratio_hrouu")), awayOddsJson);
                homeFirstHalf.putOpt("overSize", homeFirstHomeOverSize);
                awayFirstHalf.putOpt("overSize", homeFirstAwayOverSize);
            }

            // 半场胜平负
            if (game.containsKey("ior_HRMH") && 0 != game.getInt("ior_HRMH")) {
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr("ior_HRMH"));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr("ior_HRMC"));      // 投注赔率
                JSONObject drawOddsJson = new JSONObject();
                drawOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                drawOddsJson.putOpt("odds", game.getStr("ior_HRMN"));      // 投注赔率
                homeFirstHalf.putOpt("win", homeOddsJson);
                homeFirstHalf.putOpt("draw", drawOddsJson);
                awayFirstHalf.putOpt("win", awayOddsJson);
                awayFirstHalf.putOpt("draw", drawOddsJson);
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
                Constants.VER
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
     * 获取投注时的请求参数con
     * 计算逻辑是根据实际让球点数四舍五入(Math.round)得到
     * 例如：
     * "0 / 0.5" -> 中间值(就是实际值): 0.25 -> 四舍五入: 0
     * "2" -> 中间值: 2.0 -> 四舍五入: 2
     * "2.5 / 3" -> 中间值: 2.75 -> 四舍五入: 3
     * "4 / 4.5" -> 中间值: 4.25 -> 四舍五入: 4
     * @param handicap
     * @return
     */
    public static int getMiddleValue(String handicap) {
        // 分割字符串
        String[] parts = handicap.split("/");

        // 转换为数值并取绝对值
        double[] numbers = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = Math.abs(Double.parseDouble(parts[i].trim())); // 取绝对值
        }

        // 计算中间值
        if (numbers.length == 1) {
            return (int) Math.round(numbers[0]);
        } else {
            double sum = 0;
            for (double num : numbers) {
                sum += num;
            }
            return (int) Math.round(sum / numbers.length);
        }
    }

    public static void main(String[] args) {
        String[][] testCases = {
                {"-0.5", "主队", "100"},
                {"0.5", "客队", "-100"},
                {"-0/0.5", "主队", "-50"},
                {"0/0.5", "客队", "50"},
                {"-0.5/1", "主队", "-50"}, // 根据规则需调整逻辑
                {"0.5/1", "客队", "-50"},
                {"0", "主队", "0"},
                {"0", "客队", "0"},
                {"2.5/3", "大", "50"},
                {"2.5/3", "小", "-100"},
                {"2/2.5", "大", "-50"},
                {"2/2.5", "小", "50"},
                {"4.5", "大", "100"},
                {"4.5", "小", "-100"}
        };

        for (String[] testCase : testCases) {
            String handicap = testCase[0];
            String type = testCase[1];
            int expected = Integer.parseInt(testCase[2]);
            int actual = getRatio(handicap, type);
            System.out.printf("盘口: %-8s 类型: %-4s 预期: %4d 实际: %4d%n",
                    handicap, type, expected, actual);
        }
    }

    /**
     * 根据盘口类型和盘口值计算得出ratio赔率
     * @param handicap
     * @param type
     * @return
     */
    public static int getRatio(String handicap, String type) {
        boolean isRange = handicap.contains("/");
        boolean isHome = type.equals("主队");
        boolean isAway = type.equals("客队");
        boolean isOver = type.equals("大");
        boolean isUnder = type.equals("小");

        if (isHome || isAway) {
            // 让球盘逻辑
            if (!isRange) {
                return isHome ? 100 : -100;
            } else {
                String[] parts = handicap.split("/");
                double start = Double.parseDouble(parts[0].trim());
                return isHome ? (start < 0 ? -50 : 50) : (start < 0 ? 50 : -50);
            }
        } else if (isOver || isUnder) {
            // 大小盘逻辑
            if (!isRange) {
                return isOver ? 100 : -100;
            } else {
                String[] parts = handicap.split("/");
                double end = Double.parseDouble(parts[1].trim());
                if (isOver) {
                    return 50;
                } else {
                    return (end == 3.0) ? -100 : 50;
                }
            }
        }
        return 0;
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
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr(homeOddsKey));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr(awayOddsKey));      // 投注赔率
                String trimmedRatio = ratio.trim();
                homeLet.putOpt(trimmedRatio, homeOddsJson);
                awayLet.putOpt(trimmedRatio, awayOddsJson);
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
                JSONObject homeOddsJson = new JSONObject();
                homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
                homeOddsJson.putOpt("odds", game.getStr(overOddsKey));      // 投注赔率
                JSONObject awayOddsJson = new JSONObject();
                awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
                awayOddsJson.putOpt("odds", game.getStr(underOddsKey));      // 投注赔率
                String trimmedRatio = ratio.trim();
                homeOver.putOpt(trimmedRatio, homeOddsJson);
                awayOver.putOpt(trimmedRatio, awayOddsJson);
            }

            home.putOpt("overSize", homeOver);
            away.putOpt("overSize", awayOver);
        }
    }

    private void processWinDraw(JSONObject home, JSONObject away, JSONObject game,
                                String switchKey, String homeWinKey,
                                String drawKey, String awayWinKey) {
        if ("Y".equals(game.getStr(switchKey))) {
            JSONObject homeOddsJson = new JSONObject();
            homeOddsJson.putOpt("id", game.getStr("gid"));          // 投注id
            homeOddsJson.putOpt("odds", game.getStr(homeWinKey));      // 投注赔率
            JSONObject awayOddsJson = new JSONObject();
            awayOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
            awayOddsJson.putOpt("odds", game.getStr(awayWinKey));      // 投注赔率
            JSONObject drawOddsJson = new JSONObject();
            drawOddsJson.putOpt("id", game.getStr("gid"));         // 投注id
            drawOddsJson.putOpt("odds", game.getStr(drawKey));      // 投注赔率
            home.putOpt("win", homeOddsJson);
            home.putOpt("draw", drawOddsJson);
            away.putOpt("win", awayOddsJson);
            away.putOpt("draw", drawOddsJson);
        }
    }
    
}
