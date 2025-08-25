package com.example.demo.core.sites.sbo;

import cn.hutool.core.net.URLEncodeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.SboCdnApiConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.ZhiBoSchedulesType;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.config.PriorityTaskExecutor;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 盛帆网站 - 赛事列表 API具体实现 用于球队字典绑定
 */
@Slf4j
@Component
public class WebsiteSboEventsHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboEventsHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
     * 核心执行方法
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        Map<String, String> requestHeaders = buildHeaders(params);

        JSONObject finalResult = new JSONObject();
        JSONArray leagueJson = new JSONArray(); // 改为平铺的赔率数组

        String presetFilter;
        String date;
        if (ZhiBoSchedulesType.LIVESCHEDULE.getId() == params.getInt("showType")) {
            // key为l的则是滚球列表数据
            presetFilter = "Live";
            date = "All";
            // --- 构建最终成功的响应 ---
            finalResult.putOpt("success", true);
            finalResult.putOpt("code", 200);
            finalResult.putOpt("msg", "获取赛事列表数据成功");
            finalResult.putOpt("leagues", leagueJson);
            return finalResult;
        } else {
            // key为的则是今日列表数据
            presetFilter = "All";
            date = "Today";
        }

        try {
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
            // --- 转换盛帆数据为目标格式 ---
            Map<String, JSONObject> leagueMap = new LinkedHashMap<>();

            for (Object obj : eventList) {
                JSONObject event = (JSONObject) obj;

                // 联赛信息
                JSONObject tournament = event.getJSONObject("tournament");
                String leagueId = String.valueOf(tournament.getInt("id"));
                String leagueName = getLeagueName(tournament, "ZH_CN");
                boolean isLive = event.getBool("isLive", false);

                // 初始化联赛节点
                JSONObject leagueJsonObj = leagueMap.computeIfAbsent(leagueId, k -> {
                    JSONObject tmp = new JSONObject();
                    tmp.set("id", leagueId);
                    tmp.set("league", leagueName);
                    tmp.set("isLive", isLive);
                    tmp.set("events", new JSONArray());
                    return tmp;
                });

                // 当前联赛的 events
                JSONArray eventsArray = leagueJsonObj.getJSONArray("events");

                // 主队
                JSONObject home = new JSONObject();
                home.set("id", String.valueOf(event.getInt("id")));
                home.set("name", getTeamName(event.getJSONObject("homeTeam"), "ZH_CN"));
                home.set("isHome", true);
                eventsArray.add(home);

                // 客队
                JSONObject away = new JSONObject();
                away.set("id", String.valueOf(event.getInt("id")));
                away.set("name", getTeamName(event.getJSONObject("awayTeam"), "ZH_CN"));
                away.set("isHome", false);
                eventsArray.add(away);
            }

            // 收集结果
            leagueJson.addAll(leagueMap.values());

            // --- 排序：滚球赛事优先 ---
            leagueJson = leagueJson.stream()
                    .map(o -> (JSONObject) o)
                    .sorted((a, b) -> Boolean.compare(b.getBool("isLive", false), a.getBool("isLive", false)))
                    .collect(Collectors.toCollection(JSONArray::new));

            // --- 构建最终成功的响应 ---
            finalResult.putOpt("success", true);
            finalResult.putOpt("code", 200);
            finalResult.putOpt("msg", "获取赛事列表数据成功");
            finalResult.putOpt("leagues", leagueJson);

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

        log.info("赛事列表处理完成，共 {} 场比赛", leagueJson.size());
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
     * 根据赔率值判断墙类型
     */
    private String getWallType(Object oddsValue) {
        if (oddsValue instanceof Number) {
            double odds = ((Number) oddsValue).doubleValue();
            if (odds > 0) {
                return "foot"; // 正数为foot
            } else if (odds < 0) {
                return "hanging"; // 负数为hanging
            }
        }
        return "foot"; // 默认
    }

    /**
     * 获取比赛进行时间（需要根据实际情况实现）
     */
    private int getMatchTime(JSONObject eventData) {
        // 这里需要根据eventData中的时间信息计算比赛进行时间
        // 例如：从extraInfo.period和extraInfo.injuryTime计算
        // 暂时返回一个默认值
        return 45; // 假设上半场45分钟
    }
    /**
     * 计算排序权重
     * 排序规则：上半场优先，让球盘优先，盘口值从小到大
     */
    private int getSortWeight(String type, String handicapType, Object point) {
        int weight = 0;

        // 第一优先级：比赛类型（上半场优先）
        if ("firstHalf".equals(type)) {
            weight += 0; // 上半场排在前面
        } else {
            weight += 1000; // 全场排在后面
        }

        // 第二优先级：盘口类型（让球盘优先）
        if ("letBall".equals(handicapType)) {
            weight += 0; // 让球盘排在前面
        } else if ("overSize".equals(handicapType)) {
            weight += 100; // 大小盘排在后面
        }

        // 第三优先级：盘口值（从小到大）
        if (point instanceof Number) {
            double pointValue = ((Number) point).doubleValue();
            // 将盘口值转换为整数权重，确保排序正确
            weight += (int) (pointValue * 100);
        }

        return weight;
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
