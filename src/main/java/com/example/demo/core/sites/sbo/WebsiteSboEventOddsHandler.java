package com.example.demo.core.sites.sbo;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.net.URLEncodeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.SboCdnApiConstants;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 盛帆网站 - 赛事赔率 API具体实现 用于扫水时获取赔率信息
 */
@Slf4j
@Component
public class WebsiteSboEventOddsHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboEventOddsHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        String token = params.getStr("token");
        // 构造请求头
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("content-type", "application/json");
        headers.put("authorization", token);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        return headers;
    }

    @Override
    public String buildRequest(JSONObject params) { return ""; }

    /**
     * 解析响应体 - 现在主要用于处理错误情况
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult result) {
        // 基础错误检查保留
        if (result.getStatus() != 200) {
            JSONObject res = new JSONObject();
            int status = result.getStatus();
            res.putOpt("code", status);
            res.putOpt("success", false);
            res.putOpt("msg", status == 403 ? "账户登录失效" : "API请求失败");
            return res;
        }
        // 成功的具体解析将在各个步骤的方法中完成
        return JSONUtil.parseObj(result.getBody());
    }

    /**
     * 根据赛事ID获取单个赛事的详细赔率
     */
    private JSONObject step3GetOddsForEvent(ConfigAccountVO userConfig, JSONObject params, Map<String, String> headers, Long eventId, String presetFilter) {
        //log.debug("获取赛事 {} 的赔率...", eventId);

        // 注意：这里的 oddsToken 需要动态处理！以下是从第一步响应中获取的例子，您需要根据实际情况调整获取逻辑。
        // 一种可能：这个token在第一步或第二步的响应头或cookie中，需要提取并缓存。
        // 这里先用一个占位符，您必须实现获取真实token的逻辑。
        String oddsToken = params.getStr("oddsToken"); // 假设token通过参数传入，或者从某处缓存获取
        if (StringUtils.isBlank(oddsToken)) {
            log.warn("OddsToken 为空，可能无法获取赔率数据 for event: {}", eventId);
            // 可能返回一个空的JSON或抛出异常，取决于您的业务逻辑
            oddsToken = "default_token_placeholder"; // 仅为防止完全失败，实际必须处理
        }

        String variables_step3 = String.format("{\"query\":{\"id\":%d,\"filter\":\""+presetFilter+"\",\"marketGroupIds\":[0,306,307,308,309,310,311,312,313,330,331],\"excludeMarketGroupIds\":null,\"oddsCategory\":\"All\",\"priceStyle\":\"Malay\",\"oddsToken\":\"%s\"}}", eventId, oddsToken);
        String extensions_step3 = "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"2c6e1227b7089e756f66a8750cd8c6c087ad4b39792388e35e2b0374b913fec0\"}}";

        String queryParams_step3 = String.format("operationName=%s&variables=%s&extensions=%s",
                SboCdnApiConstants.OPERATION_NAME_ODDS_QUERY,
                encodeJsonParam(variables_step3),
                encodeJsonParam(extensions_step3)
        );
        String fullUrl_step3 = String.format("%s?%s", SboCdnApiConstants.API, queryParams_step3);

        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("GET", fullUrl_step3, null, headers, userConfig, false);
        } catch (Exception e) {
            log.error("第三步请求异常 (赛事ID: {}): {}", eventId, e.getMessage(), e);
            // 不要抛出异常导致整个流程终止，返回一个标记错误的对象
            JSONObject errorResult = new JSONObject();
            errorResult.putOpt("success", false);
            errorResult.putOpt("eventId", eventId);
            errorResult.putOpt("error", e.getMessage());
            return errorResult;
        }

        JSONObject response = this.parseResponse(params, resultHttp);
        // 即使单个赛事赔率获取失败，也不应该中断整个流程
        return response;
    }

    /**
     * 核心执行方法 - 整合三步流程
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        Map<String, String> requestHeaders = buildHeaders(params);

        JSONObject finalResult = new JSONObject();
        JSONArray oddsArray = new JSONArray();

        String presetFilter;
        String date;
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            // key为l的则是滚球列表数据
            presetFilter = "Live";
            date = "All";
        } else {
            // key为的则是今日列表数据
            presetFilter = "All";
            date = "Today";
        }
        Long dictEventId = params.getLong("eventId");
        try {
            // --- 第一步：获取赛事基本列表 ---
            log.info("开始执行第一步：获取赛事基本列表");
            String variables_step1 = "{\"query\":{\"sport\":\"Soccer\",\"filter\":{\"presetFilter\":\""+presetFilter+"\",\"date\":\""+date+"\"},\"oddsCategory\":\"All\",\"eventIds\":[],\"tournamentIds\":[],\"tournamentNames\":[],\"timeZone\":\"UTC__4\"}}";
            String extensions_step1 = "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"0576c8c29422ff37868b131240f644d4cfedb1be2151afbc1c57dbcb997fe9cb\"}}";

            String queryParams_step1 = String.format("operationName=%s&variables=%s&extensions=%s",
                    SboCdnApiConstants.OPERATION_NAME_EVENTS_QUERY,
                    encodeJsonParam(variables_step1),
                    encodeJsonParam(extensions_step1)
            );
            String fullUrl_step1 = String.format("%s?%s", SboCdnApiConstants.API, queryParams_step1);

            OkHttpProxyDispatcher.HttpResult resultStep1 = dispatcher.execute("GET", fullUrl_step1, null, requestHeaders, userConfig, false);
            JSONObject step1Response = this.parseResponse(params, resultStep1);
            if (!step1Response.getBool("success", false) && resultStep1.getStatus() != 200) {
                return step1Response;
            }
            JSONArray eventList = step1Response.getJSONObject("data").getJSONArray("events");
            JSONObject basicEventInfo = new JSONObject();
            for (int i = 0; i < eventList.size(); i++) {
                basicEventInfo = eventList.getJSONObject(i);
                Long eventId = basicEventInfo.getLong("id");
                if (!Objects.equals(eventId, dictEventId)) {
                    continue;
                }
                // 获取赔率信息
                JSONObject oddsInfo = step3GetOddsForEvent(userConfig, params, requestHeaders, eventId, presetFilter);
                if (oddsInfo != null) {
                    basicEventInfo.putOpt("eventOdds", oddsInfo.getJSONObject("data").getJSONArray("eventOdds"));
                    break;
                }
            }
            // --- 转换逻辑开始 ---
            JSONObject league = new JSONObject();
            league.putOpt("id", basicEventInfo.getJSONObject("tournament").getStr("id"));

            JSONArray eventsArray = new JSONArray();
            if (!basicEventInfo.isEmpty() && basicEventInfo.containsKey("eventOdds")) {
                league.putOpt("league", getLeagueName(basicEventInfo.getJSONObject("tournament"), "ZH_CN"));
                // home
                JSONObject home = convertOdds(basicEventInfo, true);
                eventsArray.add(home);
                // away
                JSONObject away = convertOdds(basicEventInfo, false);
                eventsArray.add(away);
            }
            league.putOpt("events", eventsArray);
            oddsArray.add(league);
            // --- 构建最终成功的响应 ---
            finalResult.putOpt("success", true);
            finalResult.putOpt("code", 200);
            finalResult.putOpt("msg", "获取赛事赔率数据成功");
            finalResult.putOpt("leagues", oddsArray); // 直接返回平铺后的赔率数组

        } catch (BusinessException e) {
            log.error("业务流程异常: {}", e.getMessage(), e);
            finalResult.putOpt("success", false);
            finalResult.putOpt("code", 400);
            finalResult.putOpt("msg", e.getMessage());
        } catch (Exception e) {
            log.error("执行完整流程未知异常: {}", e.getMessage(), e);
            finalResult.putOpt("success", false);
            finalResult.putOpt("code", 500);
            finalResult.putOpt("msg", "系统内部错误，获取赛事数据失败");
        }

        return finalResult;
    }

    /**
     * 获取赛事名称（支持多语言）
     */
    private String getLeagueName(JSONObject tournamentObj, String language) {
        if (tournamentObj == null) return "";
        JSONArray tournamentNames = tournamentObj.getJSONArray("tournamentName");
        for (int i = 0; i < tournamentNames.size(); i++) {
            JSONObject nameObj = tournamentNames.getJSONObject(i);
            if (language.equals(nameObj.getStr("language"))) {
                return nameObj.getStr("value");
            }
        }
        return tournamentNames.getJSONObject(0).getStr("value"); // 默认返回第一个
    }

    /**
     * 获取球队名称（支持多语言）
     */
    private String getTeamName(JSONObject teamObj, String language) {
        if (teamObj == null) return "";
        JSONArray teamNames = teamObj.getJSONArray("teamName");
        for (int i = 0; i < teamNames.size(); i++) {
            JSONObject nameObj = teamNames.getJSONObject(i);
            if (language.equals(nameObj.getStr("language"))) {
                return nameObj.getStr("value");
            }
        }
        return teamNames.getJSONObject(0).getStr("value"); // 默认返回第一个
    }

    /**
     * 把 basicEventInfo 转换成需要的目标结构
     */
    private JSONObject convertOdds(JSONObject basicEventInfo, boolean isHome) {
        JSONObject team = new JSONObject();

        int period = basicEventInfo.getJSONObject("mainMarketEventResult").getJSONObject("extraInfo").getInt("period");
        String periodStartTimeStr = basicEventInfo.getJSONObject("mainMarketEventResult").getJSONObject("extraInfo").getStr("periodStartTime");
        LocalDateTime periodStartTime = LocalDateTimeUtil.parse(periodStartTimeStr);
        Duration between = LocalDateTimeUtil.between(periodStartTime.plusHours(12), LocalDateTime.now());
        team.putOpt("id", basicEventInfo.getLong("id"));
        team.putOpt("name", isHome ? getTeamName(basicEventInfo.getJSONObject("homeTeam"), "ZH_CN") : getTeamName(basicEventInfo.getJSONObject("awayTeam"), "ZH_CN")); // 真实队名

        team.putOpt("isHome", isHome);

        // 获取比分和时间信息
        String score = "0-0";
        String session;
        long reTime;
        if (period == 1) {
            // 上半场
            session = "1H";
            reTime = between.toMinutes();
        } else if (period == 2) {
            // 下半场（全场）
            session = "2H";
            reTime = between.toMinutes() + 45;
        } else if (period == 5) {
            // 中场休息
            session = "HT";
            reTime = 45;
        } else {
            // 都不是
            session = "FT";
            reTime = 0;
        }
        JSONArray eventResult = basicEventInfo.getJSONArray("eventOdds");
        if (eventResult != null && !eventResult.isEmpty()) {
            int homeScore = eventResult.getJSONObject(0).getJSONObject("eventResult").getInt("liveHomeScore");
            int awayScore = eventResult.getJSONObject(0).getJSONObject("eventResult").getInt("liveAwayScore");
            score = homeScore + "-" + awayScore;
        }
        team.putOpt("session", session);
        team.putOpt("reTime", reTime);
        team.putOpt("score", score);

        // fullCourt / firstHalf 两个容器
        JSONObject fullCourt = new JSONObject();
        JSONObject fullLetBall = new JSONObject();
        JSONObject fullOverSize = new JSONObject();

        JSONObject firstHalf = new JSONObject();
        JSONObject halfLetBall = new JSONObject();
        JSONObject halfOverSize = new JSONObject();

        for (int i = 0; i < basicEventInfo.getJSONArray("eventOdds").size(); i++) {
            JSONObject o = basicEventInfo.getJSONArray("eventOdds").getJSONObject(i);
            String type = o.getStr("marketType");   // e.g. "Handicap" / "FH_Handicap" / "OverUnder" / "FH_OverUnder"
            double point = o.getDouble("point");

            boolean isFirstHalf = type.startsWith("FH_");
            String normalizedType = isFirstHalf ? type.substring(3) : type;

            if ("Handicap".equals(normalizedType)) {
                for (int j = 0; j < o.getJSONArray("prices").size(); j++) {
                    JSONObject price = o.getJSONArray("prices").getJSONObject(j);
                    boolean homeSide = "h".equals(price.getStr("option"));
                    if (homeSide == isHome) {
                        JSONObject node = new JSONObject();
                        node.putOpt("id", o.getLong("id"));
                        node.putOpt("handicap", point);
                        node.putOpt("odds", price.getBigDecimal("price").toString());
                        node.putOpt("wall", isHome ? "hanging" : "foot");

                        if (isFirstHalf) {
                            halfLetBall.putOpt(getHandicapRange(point), node);
                        } else {
                            fullLetBall.putOpt(getHandicapRange(point), node);
                        }
                    }
                }
            } else if ("OverUnder".equals(normalizedType)) {
                for (int j = 0; j < o.getJSONArray("prices").size(); j++) {
                    JSONObject price = o.getJSONArray("prices").getJSONObject(j);
                    JSONObject node = new JSONObject();
                    node.putOpt("id", o.getLong("id"));
                    node.putOpt("handicap", getHandicapRange(point));
                    node.putOpt("odds", price.getBigDecimal("price").toString());

                    if (isFirstHalf) {
                        halfOverSize.putOpt(getHandicapRange(point), node);
                    } else {
                        fullOverSize.putOpt(getHandicapRange(point), node);
                    }
                }
            }
            // TODO: 其他 marketType (CorrectScore, OddEven, TotalGoal, LiveScore) 也要映射
        }

        // 装配结构
        fullCourt.putOpt("letBall", fullLetBall);
        fullCourt.putOpt("overSize", fullOverSize);
        firstHalf.putOpt("letBall", halfLetBall);
        firstHalf.putOpt("overSize", halfOverSize);

        team.putOpt("fullCourt", fullCourt);
        team.putOpt("firstHalf", firstHalf);

        return team;
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

            return 0.0 == handicap ? "0" : String.valueOf(handicap);
        } else {
            // 如果不是 0.5 的倍数，返回一个范围
            double lowerBound = handicap - 0.25;
            double upperBound = handicap + 0.25;
            String lowerBoundStr = 0.0 == lowerBound ? "0" : String.valueOf(handicap);
            String upperBoundStr = 0.0 == upperBound ? "0" : String.valueOf(upperBound);
            return lowerBoundStr + "-" + upperBoundStr;
        }
    }

    /**
     * 对JSON参数进行编码，但保留 {} 符号
     */
    private String encodeJsonParam(String json) {
        if (json == null) {
            return "";
        }
        // 先编码整个字符串
        String encoded = URLEncodeUtil.encodeAll(json);
        // 然后将编码后的 {} 符号解码回原始字符
        encoded = encoded.replace("%7B", "{").replace("%7D", "}");
        return encoded;
    }
}
