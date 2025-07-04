package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.NumberUtil;
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
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 智博网站 - 赛事列表 API具体实现 用于扫水时获取最新的赔率
 */
@Slf4j
@Component
public class WebsiteXinBaoEventOddsNewHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventOddsNewHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        String filter;
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            showType = "live";  // 滚球赛事
            rType = "rrnou";  // 滚球赛事
            filter = "RB";
        } else {
            showType = "today";  // 今日赛事
            rType = "rnou";  // 今日赛事
            filter = "FT";
        }
        // 构造请求体
        String requestBody = String.format("p=get_game_list&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&rtype=%s&ltype=3&filter=%s&cupFantasy=N&sorttype=L&isFantasy=N&ts=%s",
                params.getStr("uid"),
                Constants.VER,
                showType,
                rType,
                filter,
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
        JSONObject responseJson = new JSONObject();
        if (response.getStatus() != 200) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失效");
            return responseJson;
        }

        Document docResult = XmlUtil.readXML(response.body());
        Object original = XmlUtil.getByXPath("//serverresponse/original", docResult, XPathConstants.STRING);
        if (ObjectUtil.isEmpty(original)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取账户赛事失败");
            return responseJson;
        }

        String username = params.getStr("adminUsername");
        String oddsFormatType = params.getStr("oddsFormatType");
        JSONObject originalJson = JSONUtil.parseObj(original);
        Map<String, JSONObject> leagueMap = new LinkedHashMap<>(); // 保持顺序

        originalJson.forEach(entry -> {
            JSONObject gameJson = JSONUtil.parseObj(entry.getValue());

            String lid = gameJson.getStr("LID");
            String gid = gameJson.getStr("GID");
            String ecid = gameJson.getStr("ECID");
            String leagueName = gameJson.getStr("LEAGUE");
            String homeTeamId = gameJson.getStr("TEAM_H_ID");
            String awayTeamId = gameJson.getStr("TEAM_C_ID");
            String home = gameJson.getStr("TEAM_H");
            String away = gameJson.getStr("TEAM_C");
            String score = gameJson.getStr("SCORE_H", "") + "-" + gameJson.getStr("SCORE_C", "");
            String session = gameJson.getStr("NOW_MODEL");
            String midfield = gameJson.getStr("MIDFIELD");
            String reTimeSet = gameJson.getStr("RETIMESET");

            // 半场/全场判定
            String type;
            if ("HT".equals(session)) type = "firstHalf";
            else {
                type = "fullCourt";
            }

            // 时间处理
            int reTime = 0;
            if (StringUtils.isNotBlank(reTimeSet)) {
                String[] parts = reTimeSet.split("\\^");
                if (parts.length > 1) {
                    String[] timeParts = parts[1].split(":");
                    String minuteStr = timeParts[0].replaceAll("\\D", "");
                    reTime = NumberUtil.parseInt(minuteStr, 0);
                }
            }
            if ("Y".equals(midfield)) session = "HT";

            // 获取联赛容器
            JSONObject league = leagueMap.computeIfAbsent(lid, k -> {
                JSONObject obj = new JSONObject();
                obj.putOpt("id", lid);
                obj.putOpt("ecid", ecid);
                obj.putOpt("league", leagueName);
                obj.putOpt("events", new JSONArray());
                return obj;
            });

            // 获取赛事容器（home 和 away 都需要）
            JSONArray events = league.getJSONArray("events");

            // 主队和客队对象
            JSONObject homeTeam = new JSONObject();
            homeTeam.putOpt("id", homeTeamId);
            homeTeam.putOpt("name", home);
            homeTeam.putOpt("isHome", true);
            homeTeam.putOpt("score", score);
            homeTeam.putOpt("session", session);
            homeTeam.putOpt("reTime", reTime);
            homeTeam.putOpt("fullCourt", new JSONObject());
            homeTeam.putOpt("firstHalf", new JSONObject());

            JSONObject awayTeam = new JSONObject();
            awayTeam.putOpt("id", awayTeamId);
            awayTeam.putOpt("name", away);
            awayTeam.putOpt("isHome", false);
            awayTeam.putOpt("score", score);
            awayTeam.putOpt("session", session);
            awayTeam.putOpt("reTime", reTime);
            awayTeam.putOpt("fullCourt", new JSONObject());
            awayTeam.putOpt("firstHalf", new JSONObject());

            // 盘口组装逻辑
            BiConsumer<JSONObject, Boolean> processOdds = (team, isHome) -> {
                String prefix = type.equals("firstHalf") ? "H" : "";

                // 让球盘
                if (gameJson.containsKey("IOR_" + prefix + "REH") && StringUtils.isNotBlank(gameJson.getStr("IOR_" + prefix + "REH"))) {
                    JSONObject letBall = (JSONObject) team.getJSONObject(type).computeIfAbsent("letBall", k -> new JSONObject());
                    String ratio = gameJson.getStr("RATIO_" + prefix + "RE", "0");
                    String oddsKey = ratio.contains("/") ? ratio : ratio.replace(".0", "");
                    String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble("IOR_" + prefix + (isHome ? "REH" : "REC")));

                    JSONObject item = new JSONObject();
                    item.putOpt("id", gid);
                    item.putOpt("odds", odds);
                    item.putOpt("oddFType", oddsFormatType);
                    item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                    item.putOpt("wtype", prefix + "RE");
                    item.putOpt("rtype", prefix + (isHome ? "REH" : "REC"));
                    item.putOpt("choseTeam", isHome ? "H" : "C");
                    item.putOpt("con", getMiddleValue(ratio));
                    item.putOpt("ratio", isHome ? getRatio(ratio, "主队") : -getRatio(ratio, "客队"));
                    item.putOpt("handicap", (isHome && !"0".equals(ratio) ? "-" : "") + ratio);
                    item.putOpt("wall", isHome ? "hanging" : "foot");

                    letBall.putOpt(getHandicapRange(oddsKey), item);

                    // 记录主队的赔率
                    String key = isHome ? "H" : "C";
                    apiUrlService.updateOddsCache(username, gid + key, Double.parseDouble(odds));
                    // 记录客队的赔率
                    apiUrlService.updateOddsCache(username, gid + key, Double.parseDouble(odds));
                }

                // 大小盘
                if (gameJson.containsKey("IOR_" + prefix + "ROUH") && StringUtils.isNotBlank(gameJson.getStr("IOR_" + prefix + "ROUH"))) {
                    JSONObject overSize = (JSONObject) team.getJSONObject(type).computeIfAbsent("overSize", k -> new JSONObject());
                    String ratio = gameJson.getStr("RATIO_" + prefix + "ROUO", "0");
                    String oddsKey = ratio.contains("/") ? ratio : ratio.replace(".0", "");
                    String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble("IOR_" + prefix + (isHome ? "ROUC" : "ROUH")));

                    JSONObject item = new JSONObject();
                    item.putOpt("id", gid);
                    item.putOpt("odds", odds);
                    item.putOpt("oddFType", oddsFormatType);
                    item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
                    item.putOpt("wtype", prefix + "ROU");
                    item.putOpt("rtype", prefix + (isHome ? "ROUC" : "ROUH"));
                    item.putOpt("choseTeam", isHome ? "C" : "H");
                    item.putOpt("con", getMiddleValue(ratio));
                    item.putOpt("ratio", isHome ? getRatio(ratio, "大") : -getRatio(ratio, "小"));
                    item.putOpt("handicap", ratio);

                    overSize.putOpt(getHandicapRange(oddsKey), item);

                    // 记录主队的赔率
                    String key = isHome ? "H" : "C";
                    apiUrlService.updateOddsCache(username, gid + key, Double.parseDouble(odds));
                    // 记录客队的赔率
                    apiUrlService.updateOddsCache(username, gid + key, Double.parseDouble(odds));
                }
            };

            processOdds.accept(homeTeam, true);
            processOdds.accept(awayTeam, false);

            events.add(homeTeam);
            events.add(awayTeam);
        });

        JSONArray data = new JSONArray();
        data.addAll(leagueMap.values());

        JSONArray finalResult = mergeTeamsByTeamId(data);

        responseJson.putOpt("success", true);
        responseJson.putOpt("code", 0);
        responseJson.putOpt("msg", "成功");
        responseJson.putOpt("leagues", finalResult);
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

    /**
     * 替换handicap符号
     * @param handicap
     * @return
     */
    public String getHandicapRange(String handicap) {
        return handicap.replaceAll(" / ", "-");
    }

    /**
     * 将现有解析后的 leagues 数据进行球队合并
     */
    public JSONArray mergeTeamsByTeamId(JSONArray originalLeagues) {
        JSONArray result = new JSONArray();

        for (Object leagueObj : originalLeagues) {
            JSONObject league = (JSONObject) leagueObj;

            // 新 league 对象
            JSONObject newLeague = new JSONObject();
            newLeague.putOpt("id", league.getStr("id"));
            newLeague.putOpt("ecid", league.getStr("ecid"));
            newLeague.putOpt("league", league.getStr("league"));

            JSONArray newEvents = new JSONArray();
            Map<String, JSONObject> teamMap = new LinkedHashMap<>();

            JSONArray events = league.getJSONArray("events");
            for (Object eventObj : events) {
                JSONObject team = (JSONObject) eventObj;
                String teamId = team.getStr("id");

                // 合并逻辑
                JSONObject existing = teamMap.get(teamId);
                if (existing == null) {
                    // 初次出现，放入 map
                    teamMap.put(teamId, JSONUtil.parseObj(team));
                } else {
                    // 合并 fullCourt
                    mergeHandicap(existing.getJSONObject("fullCourt"), team.getJSONObject("fullCourt"));
                    mergeHandicap(existing.getJSONObject("firstHalf"), team.getJSONObject("firstHalf"));
                }
            }

            // 添加合并后的队伍
            newEvents.addAll(teamMap.values());
            newLeague.putOpt("events", newEvents);
            result.add(newLeague);
        }

        return result;
    }

    private void mergeHandicap(JSONObject target, JSONObject source) {
        for (String key : source.keySet()) {
            JSONObject targetGroup = (JSONObject) target.computeIfAbsent(key, k -> new JSONObject());
            JSONObject sourceGroup = source.getJSONObject(key);
            for (String handicapKey : sourceGroup.keySet()) {
                targetGroup.putOpt(handicapKey, sourceGroup.get(handicapKey));
            }
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
     * 如果是香港盘，则不需要计算
     */
    private String calculateOddsValue(String oddsType, double value) {
        if (oddsType.equals(XinBaoOddsFormatType.RM.getCurrencyCode())) {
            return value > 1.0 ?
                    String.format("%.3f", value - 2.0) :
                    String.format("%.3f", value);
        } else {
            return String.format("%.3f", value);
        }
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
    
}
