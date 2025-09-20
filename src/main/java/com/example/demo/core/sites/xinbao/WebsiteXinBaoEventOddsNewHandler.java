package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoEventOddsNewHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        return String.format("p=get_game_list&uid=%s&ver=%s&langx=zh-cn&gtype=ft&showtype=%s&rtype=%s&ltype=3&filter=%s&cupFantasy=N&sorttype=L&isFantasy=N&ts=%s",
                params.getStr("uid"),
                Constants.VER,
                showType,
                rType,
                filter,
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
        JSONObject responseJson = new JSONObject();
        if (response.getStatus() != 200) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失效");
            return responseJson;
        }

        Document docResult = XmlUtil.readXML(response.getBody());
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
            String sessionValue;
            if ("HT".equals(session)) {
                type = "firstHalf";
                sessionValue = "1H";
            } else if ("FT".equals(session)) {
                sessionValue = "2H";
                type = "fullCourt";
            } else {
                sessionValue = "HT";
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
            if ("Y".equals(midfield)) sessionValue = "HT";

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
            homeTeam.putOpt("session", sessionValue);
            homeTeam.putOpt("reTime", reTime);
            homeTeam.putOpt("fullCourt", new JSONObject());
            homeTeam.putOpt("firstHalf", new JSONObject());

            JSONObject awayTeam = new JSONObject();
            awayTeam.putOpt("id", awayTeamId);
            awayTeam.putOpt("name", away);
            awayTeam.putOpt("isHome", false);
            awayTeam.putOpt("score", score);
            awayTeam.putOpt("session", sessionValue);
            awayTeam.putOpt("reTime", reTime);
            awayTeam.putOpt("fullCourt", new JSONObject());
            awayTeam.putOpt("firstHalf", new JSONObject());

            // ============= 盘口组装逻辑（全场 / 半场 / 子盘口） =============
            BiConsumer<JSONObject, Boolean> processOdds = (team, isHome) -> {
                // 处理盘口（全场 + 半场）
                parseOddsBlock(gameJson, gid, oddsFormatType, team, isHome);
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

    public static JSONArray convertOdds(JSONArray input) {
        // leagueId -> leagueJson
        Map<String, JSONObject> leagueMap = new LinkedHashMap<>();

        for (Object obj : input) {
            JSONObject item = (JSONObject) obj;

            String leagueName = item.getStr("league");
            String ecid = item.getStr("ecid");
            String leagueKey = ecid + "_" + leagueName;

            // 获取或创建 league
            JSONObject leagueJson = leagueMap.computeIfAbsent(leagueKey, k -> {
                JSONObject l = new JSONObject();
                l.set("id", ecid);
                l.set("league", leagueName);
                l.set("events", new JSONArray());
                return l;
            });

            // 在 events 里查找当前 event
            JSONArray events = leagueJson.getJSONArray("events");
            String eventId = item.getStr("eventId");
            JSONObject eventJson = null;
            for (Object e : events) {
                JSONObject ej = (JSONObject) e;
                if (eventId.equals(ej.getStr("id"))) {
                    eventJson = ej;
                    break;
                }
            }
            if (eventJson == null) {
                eventJson = new JSONObject();
                eventJson.set("id", eventId);
                eventJson.set("name", item.getStr("homeTeam"));
                eventJson.set("isHome", true);
                eventJson.set("score", item.getStr("score"));
                eventJson.set("session", item.getStr("session"));
                eventJson.set("reTime", item.getInt("reTime"));

                // 初始化盘口结构
                JSONObject fullCourt = new JSONObject();
                fullCourt.set("letBall", new JSONObject());
                fullCourt.set("overSize", new JSONObject());
                JSONObject firstHalf = new JSONObject();
                firstHalf.set("letBall", new JSONObject());
                firstHalf.set("overSize", new JSONObject());

                eventJson.set("fullCourt", fullCourt);
                eventJson.set("firstHalf", firstHalf);

                events.add(eventJson);
            }

            // 确定盘口维度
            String type = item.getStr("type"); // fullCourt or firstHalf
            String handicapType = item.getStr("handicapType"); // letBall or overSize

            JSONObject targetBlock = eventJson.getJSONObject(type);
            JSONObject handicapBlock = targetBlock.getJSONObject(handicapType);

            // 添加 home 盘口
            JSONObject homeObj = new JSONObject();
            homeObj.set("id", item.getStr("gid"));
            homeObj.set("odds", item.getStr("homeOdds"));
            homeObj.set("oddFType", item.getStr("homeOddFType"));
            homeObj.set("gtype", item.getStr("homeGtype"));
            homeObj.set("wtype", item.getStr("homeWtype"));
            homeObj.set("rtype", item.getStr("homeRtype"));
            homeObj.set("choseTeam", item.getStr("homeChoseTeam"));
            homeObj.set("con", item.getInt("homeCon"));
            homeObj.set("ratio", item.getInt("homeRatio"));
            homeObj.set("handicap", item.get("homeHandicap"));
            homeObj.set("wall", item.getStr("homeWall"));

            handicapBlock.set(item.getStr("homeHandicap"), homeObj);

            // 添加 away 盘口
            JSONObject awayObj = new JSONObject();
            awayObj.set("id", item.getStr("gid"));
            awayObj.set("odds", item.getStr("awayOdds"));
            awayObj.set("oddFType", item.getStr("awayOddFType"));
            awayObj.set("gtype", item.getStr("awayGtype"));
            awayObj.set("wtype", item.getStr("awayWtype"));
            awayObj.set("rtype", item.getStr("awayRtype"));
            awayObj.set("choseTeam", item.getStr("awayChoseTeam"));
            awayObj.set("con", item.getInt("awayCon"));
            awayObj.set("ratio", item.getInt("awayRatio"));
            awayObj.set("handicap", item.get("awayHandicap"));
            awayObj.set("wall", item.getStr("awayWall"));

            // 可能和 homeHandicap 一样，这里我加个后缀避免覆盖
            String awayKey = String.valueOf(item.get("awayHandicap"));
            if (handicapBlock.containsKey(awayKey)) {
                awayKey = awayKey + "_opponent";
            }
            handicapBlock.set(awayKey, awayObj);
        }

        return new JSONArray(leagueMap.values());
    }

    /**
     * 公共盘口解析逻辑, 根据明确的字段名解析全场 / 半场的让球盘和大小盘
     */
    private void parseOddsBlock(JSONObject gameJson, String gid, String oddsFormatType, JSONObject team, boolean isHome) {

        // 全场让球key
        String ratioFull = "RATIO_RE";                  // 让球分
        String keyHomeOddsFullLetBall = "IOR_REH";      // 滚球类型获取赔率字段key中有E字符
        String keyAwayOddsFullLetBall = "IOR_REC";      // 滚球类型获取赔率字段key中有E字符
        // 全场大小key
        String homeRatioFullOverSize = "RATIO_ROUO";    // 大小球分
        String awayRatioFullOverSize = "RATIO_ROUU";    // 大小球分
        String keyHomeOddsFullOverSize = "IOR_ROUC";    // 滚球类型获取赔率字段key中有R字符
        String keyAwayOddsFullOverSize = "IOR_ROUH";    // 滚球类型获取赔率字段key中有R字符
        // 半场让球key
        String ratioFirst = "RATIO_HRE";                // 让球分
        String keyHomeOddsFirstLetBall = "IOR_HREH";    // 滚球类型获取赔率字段key中有E字符
        String keyAwayOddsFirstLetBall = "IOR_HREC";    // 滚球类型获取赔率字段key中有E字符
        // 半场大小key
        String homeRatioFirstOverSize = "RATIO_HROUO";  // 大小球分
        String awayRatioFirstOverSize = "RATIO_HROUU";  // 大小球分
        String keyHomeOddsFirstOverSize = "IOR_HROUC";  // 滚球类型获取赔率字段key中有R字符
        String keyAwayOddsFirstOverSize = "IOR_HROUH";  // 滚球类型获取赔率字段key中有R字符
        // ========== 全场让球 ==========
        if (gameJson.containsKey(keyHomeOddsFullLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullLetBall))) {
            String homeHandicap = "";
            if ("0".equals(gameJson.getStr(ratioFull))) {
                homeHandicap = gameJson.getStr(ratioFull);
            } else {
                homeHandicap = "-" + gameJson.getStr(ratioFull);
            }
            String key = isHome ? keyHomeOddsFullLetBall : keyAwayOddsFullLetBall;
            String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(key));
            JSONObject letBall = (JSONObject) team.getJSONObject("fullCourt")
                    .computeIfAbsent("letBall", k -> new JSONObject());

            JSONObject item = new JSONObject();
            item.putOpt("id", gid);
            item.putOpt("odds", odds);
            item.putOpt("oddFType", oddsFormatType);
            item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
            item.putOpt("wtype", "RE");
            item.putOpt("rtype", isHome ? "REH" : "REC");
            item.putOpt("choseTeam", isHome ? "H" : "C");
            item.putOpt("con", isHome ? getMiddleValue(homeHandicap) : -Math.abs(getMiddleValue(homeHandicap)));
            item.putOpt("ratio", isHome ? getRatio(ratioFull, "主队") : -getRatio(ratioFull, "客队"));
            item.putOpt("handicap", isHome ? homeHandicap : gameJson.getStr(ratioFull));
            item.putOpt("wall", isHome ? "hanging" : "foot");

            letBall.putOpt(getHandicapRange(ratioFull), item);
        }

        // ========== 全场大小 ==========
        if (gameJson.containsKey(keyHomeOddsFullOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFullOverSize))) {
            String key = isHome ? keyHomeOddsFullOverSize : keyAwayOddsFullOverSize;
            String ratioFullOverSize = gameJson.getStr(isHome ? homeRatioFullOverSize : awayRatioFullOverSize);
            String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(key));
            JSONObject overSize = (JSONObject) team.getJSONObject("fullCourt")
                    .computeIfAbsent("overSize", k -> new JSONObject());

            JSONObject item = new JSONObject();
            item.putOpt("id", gid);
            item.putOpt("odds", odds);
            item.putOpt("oddFType", oddsFormatType);
            item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
            item.putOpt("wtype", "ROU");
            item.putOpt("rtype", isHome ? "ROUC" : "ROUH");
            item.putOpt("choseTeam", isHome ? "C" : "H");
            item.putOpt("con", isHome ? getMiddleValue(gameJson.getStr(homeRatioFullOverSize)) : -Math.abs(getMiddleValue(gameJson.getStr(homeRatioFullOverSize))));
            item.putOpt("ratio", isHome ? getRatio(ratioFullOverSize, "大") : -getRatio(ratioFullOverSize, "小"));
            item.putOpt("handicap", ratioFullOverSize);

            overSize.putOpt(getHandicapRange(ratioFullOverSize), item);
        }

        // ========== 半场让球 ==========
        if (gameJson.containsKey(keyHomeOddsFirstLetBall) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstLetBall))) {

            String homeHandicap = "";
            if ("0".equals(gameJson.getStr(ratioFirst))) {
                homeHandicap = gameJson.getStr(ratioFirst);
            } else {
                homeHandicap = "-" + gameJson.getStr(ratioFirst);
            }
            String key = isHome ? keyHomeOddsFirstLetBall : keyAwayOddsFirstLetBall;
            String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(key));
            JSONObject letBall = (JSONObject) team.getJSONObject("firstHalf")
                    .computeIfAbsent("letBall", k -> new JSONObject());

            JSONObject item = new JSONObject();
            item.putOpt("id", gid);
            item.putOpt("odds", odds);
            item.putOpt("oddFType", oddsFormatType);
            item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
            item.putOpt("wtype", "HRE");
            item.putOpt("rtype", isHome ? "HREH" : "HREC");
            item.putOpt("choseTeam", isHome ? "H" : "C");
            item.putOpt("con", isHome ? getMiddleValue(gameJson.getStr(ratioFirst)) : -Math.abs(getMiddleValue(gameJson.getStr(ratioFirst))));
            item.putOpt("ratio", isHome ? getRatio(gameJson.getStr(ratioFirst), "主队") : -getRatio(gameJson.getStr(ratioFirst), "客队"));
            item.putOpt("handicap", isHome ? homeHandicap : gameJson.getStr(ratioFirst));
            item.putOpt("wall", isHome ? "hanging" : "foot");

            letBall.putOpt(getHandicapRange(ratioFirst), item);
        }

        // ========== 半场大小 ==========
        if (gameJson.containsKey(keyHomeOddsFirstOverSize) && StringUtils.isNotBlank(gameJson.getStr(keyHomeOddsFirstOverSize))) {
            String ratioFirstOverSize = gameJson.getStr(isHome ? homeRatioFirstOverSize : awayRatioFirstOverSize);
            String key = isHome ? keyHomeOddsFirstOverSize : keyAwayOddsFirstOverSize;
            String odds = calculateOddsValue(oddsFormatType, gameJson.getDouble(key));
            JSONObject overSize = (JSONObject) team.getJSONObject("firstHalf")
                    .computeIfAbsent("overSize", k -> new JSONObject());

            JSONObject item = new JSONObject();
            item.putOpt("id", gid);
            item.putOpt("odds", odds);
            item.putOpt("oddFType", oddsFormatType);
            item.putOpt("gtype", gameJson.getStr("MT_GTYPE"));
            item.putOpt("wtype", "HROU");
            item.putOpt("rtype", isHome ? "HROUC" : "HROUH");
            item.putOpt("choseTeam", isHome ? "C" : "H");
            item.putOpt("con", isHome ? getMiddleValue(gameJson.getStr(homeRatioFirstOverSize)) : -Math.abs(getMiddleValue(gameJson.getStr(awayRatioFirstOverSize))));
            item.putOpt("ratio", isHome ? getRatio(gameJson.getStr(homeRatioFirstOverSize), "大") : -getRatio(gameJson.getStr(awayRatioFirstOverSize), "小"));
            item.putOpt("handicap", ratioFirstOverSize);

            overSize.putOpt(getHandicapRange(ratioFirstOverSize), item);
        }
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

    // 公共字段抽取方法
    private JSONObject buildBaseLeagueJson(JSONObject gameJson, String lid, String gid, String ecid, String league, String session, String half, int reTime, String type, String score) {
        JSONObject base = new JSONObject();
        base.putOpt("id", lid);
        base.putOpt("league", league);
        base.putOpt("type", type);
        base.putOpt("session", session);
        base.putOpt("reTime", reTime);
        base.putOpt("eventId", lid);
        base.putOpt("gid", gid);
        base.putOpt("ecid", ecid);
        base.putOpt("homeTeam", gameJson.getStr("TEAM_H"));
        base.putOpt("awayTeam", gameJson.getStr("TEAM_C"));
        base.putOpt("score", score);
        return base;
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
