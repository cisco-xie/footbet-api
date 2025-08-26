package com.example.demo.core.sites.sbo;

import cn.hutool.core.date.LocalDateTimeUtil;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 盛帆网站 - 赛事列表 API具体实现 用于操作页面查看赛事列表
 * 修改：整合三步流程（赛事列表 -> 实时比分 -> 详细赔率）
 */
@Slf4j
@Component
public class WebsiteSboEventListHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboEventListHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
     * 第二步：获取所有比赛的实时比分
     */
    private JSONObject step2GetLiveScores(ConfigAccountVO userConfig, JSONObject params, Map<String, String> headers, String presetFilter, String date) {
        log.info("开始执行第二步：获取实时比分列表");

        String variables_step2 = "{\"query\":{\"sport\":\"Soccer\",\"filter\":{\"presetFilter\":\""+presetFilter+"\",\"date\":\""+date+"\"},\"oddsCategory\":\"All\",\"eventIds\":[],\"tournamentIds\":[],\"tournamentNames\":[],\"timeZone\":\"UTC__4\"}}";
        String extensions_step2 = "{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"f74a72f3ca60f047093e6b79d76adfe890bb20f44fe243250bdbf767cca22858\"}}";

        String queryParams_step2 = String.format("operationName=%s&variables=%s&extensions=%s",
                SboCdnApiConstants.OPERATION_NAME_EVENT_RESULTS_QUERY,
                encodeJsonParam(variables_step2),
                encodeJsonParam(extensions_step2)
        );
        String fullUrl_step2 = String.format("%s?%s", SboCdnApiConstants.API, queryParams_step2);

        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("GET", fullUrl_step2, null, headers, userConfig, false);
        } catch (Exception e) {
            log.error("[盛帆网站]-[赛事列表]第二步获取比分请求异常: {}", e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }

        JSONObject response = this.parseResponse(params, resultHttp);
        if (!response.getBool("success", false) && resultHttp.getStatus() != 200) {
            throw new BusinessException(SystemError.SYS_400);
        }
        // 返回第二步的原始数据，供主流程解析
        return response;
    }

    /**
     * 第三步：根据赛事ID获取单个赛事的详细赔率
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
        JSONArray flattenedOddsArray = new JSONArray(); // 改为平铺的赔率数组

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
        // 声明线程池在外面，确保finally中可以关闭
        ExecutorService eventsExecutor = null;

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
            log.info("第一步成功，获取到 {} 场赛事", eventList.size());

            // 盛帆的今日赛事列表存在滚球数据，如果是查询今日数据就把滚球数据删掉
            if (ZhiBoSchedulesType.LIVESCHEDULE.getId() != params.getInt("showType")) {
                // 直接在 eventList 中删除滚球（isLive = true）
                for (int i = eventList.size() - 1; i >= 0; i--) {
                    JSONObject event = eventList.getJSONObject(i);
                    if (event.getBool("isLive", false)) {
                        eventList.remove(i);
                    }
                }
            }
            // --- 第二步：获取实时比分 ---
//            JSONObject step2Response = step2GetLiveScores(userConfig, params, requestHeaders, presetFilter, date);
//            JSONArray liveScoresList = step2Response.getJSONObject("data").getJSONArray("events");
//            log.info("第二步成功，获取到 {} 场赛事的比分", liveScoresList.size());
//
//            // 创建Map便于根据ID查找比分信息
//            Map<Long, JSONObject> liveScoreMap = new HashMap<>();
//            for (int i = 0; i < liveScoresList.size(); i++) {
//                JSONObject scoreEvent = liveScoresList.getJSONObject(i);
//                liveScoreMap.put(scoreEvent.getLong("id"), scoreEvent);
//            }

            // --- 第三步：使用线程池并行获取赔率 ---
            log.info("开始执行第三步：并行获取赛事赔率 (共 {} 场)...", eventList.size());

            int cpuCoreCount = Runtime.getRuntime().availableProcessors();
            int corePoolSize = Math.min(eventList.size(), cpuCoreCount * 4);
            int maxPoolSize = Math.min(eventList.size(), 100);

            eventsExecutor = new ThreadPoolExecutor(
                    corePoolSize,
                    maxPoolSize,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(500),
                    new ThreadFactoryBuilder().setNameFormat("odds-task-%d").build(),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

            // 存储每个赛事的完整信息（基础信息+比分+赔率）
            Map<Long, JSONObject> eventFullInfoMap = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < eventList.size(); i++) {
                JSONObject basicEventInfo = eventList.getJSONObject(i);
                Long eventId = basicEventInfo.getLong("id");

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        // 获取基础赛事信息
                        JSONObject eventData = basicEventInfo.clone();
                        // 获取赔率信息
                        JSONObject oddsInfo = step3GetOddsForEvent(userConfig, params, requestHeaders, eventId, presetFilter);
                        eventData.putOpt("oddsInfo", oddsInfo);
                        eventFullInfoMap.put(eventId, eventData);
                    } catch (Exception e) {
                        log.error("处理赛事 {} 数据失败: {}", eventId, e.getMessage());
                        JSONObject errorEvent = null;
                        try {
                            errorEvent = basicEventInfo.clone();
                        } catch (CloneNotSupportedException ex) {
                            throw new RuntimeException(ex);
                        }
                        errorEvent.putOpt("error", e.getMessage());
                        eventFullInfoMap.put(eventId, errorEvent);
                    }
                }, eventsExecutor);

                futures.add(future);
            }

            // 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            log.info("第三步完成，所有数据获取完毕");

            // --- 平铺数据结构：将赔率数组展开 ---
            log.info("开始平铺数据结构...");
            int totalOddsCount = 0;

            // 按照eventList的顺序来遍历，保持原始顺序
            for (int i = 0; i < eventList.size(); i++) {
                JSONObject basicEventInfo = eventList.getJSONObject(i);
                Long eventId = basicEventInfo.getLong("id");

                // 从eventFullInfoMap中获取对应的完整信息
                JSONObject eventData = eventFullInfoMap.get(eventId);
                if (eventData == null || eventData.containsKey("error")) {
                    continue; // 跳过有错误或不存在的数据
                }

                JSONObject oddsInfo = eventData.getJSONObject("oddsInfo");

                if (oddsInfo != null && oddsInfo.containsKey("data")) {
                    JSONArray eventOddsArray = oddsInfo.getJSONObject("data").getJSONArray("eventOdds");

                    if (eventOddsArray != null && !eventOddsArray.isEmpty()) {
                        // 获取赛事基础信息
                        String homeTeam = getTeamName(eventData.getJSONObject("homeTeam"), "ZH_CN");
                        String awayTeam = getTeamName(eventData.getJSONObject("awayTeam"), "ZH_CN");
                        String league = getLeagueName(eventData.getJSONObject("tournament"), "ZH_CN");

                        String session;
                        long reTime = 0;
                        if (basicEventInfo.getBool("isLive", false)) {
                            // 只有滚球才有时长信息
                            int period = basicEventInfo.getJSONObject("mainMarketEventResult").getJSONObject("extraInfo").getInt("period");
                            String periodStartTimeStr = basicEventInfo.getJSONObject("mainMarketEventResult").getJSONObject("extraInfo").getStr("periodStartTime");
                            LocalDateTime periodStartTime = LocalDateTimeUtil.parse(periodStartTimeStr);
                            Duration between = LocalDateTimeUtil.between(periodStartTime.plusHours(12), LocalDateTime.now());
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
                        }
                        // 先收集该赛事的所有赔率记录，然后排序
                        List<JSONObject> eventOddsList = new ArrayList<>();

                        for (int j = 0; j < eventOddsArray.size(); j++) {
                            JSONObject oddsRecord = eventOddsArray.getJSONObject(j);

                            String marketType = oddsRecord.getStr("marketType");
                            String type = "fullCourt"; // 默认全场
                            String handicapType = ""; // 盘口类型

                            // 判断是全场还是上半场
                            if (marketType.startsWith("FH_")) {
                                type = "firstHalf"; // 上半场
                                marketType = marketType.substring(3); // 去掉FH_前缀
                            }

                            // 根据市场类型设置handicapType
                            switch (marketType) {
                                case "Handicap":
                                    handicapType = "letBall"; // 让球盘
                                    break;
                                case "OverUnder":
                                    handicapType = "overSize"; // 大小盘
                                    break;
                                default:
                                    // 其他类型跳过或不处理
                                    continue;
                            }

                            // 获取比分和时间信息
                            String score = "0-0";
                            JSONObject eventResult = oddsRecord.getJSONObject("eventResult");
                            if (eventResult != null && !eventResult.isEmpty()) {
                                int homeScore = eventResult.getInt("liveHomeScore");
                                int awayScore = eventResult.getInt("liveAwayScore");
                                score = homeScore + "-" + awayScore;
                            }

                            JSONObject flattenedRecord = new JSONObject();

                            // 基础信息
                            flattenedRecord.putOpt("id", oddsRecord.getLong("id")); // 赔率ID
                            flattenedRecord.putOpt("league", league);
                            flattenedRecord.putOpt("type", type);
                            flattenedRecord.putOpt("handicapType", handicapType);
                            flattenedRecord.putOpt("reTime", reTime);
                            flattenedRecord.putOpt("eventId", eventId);
                            flattenedRecord.putOpt("homeTeam", homeTeam);
                            flattenedRecord.putOpt("awayTeam", awayTeam);
                            flattenedRecord.putOpt("score", score);

                            // 赔率信息 - 根据不同的市场类型处理
                            Object point = oddsRecord.get("point");

                            if ("Handicap".equals(marketType)) {
                                // 让球盘处理
                                flattenedRecord.putOpt("homeHandicap", point);
                                if (point instanceof Number) {
                                    double handicap = ((Number) point).doubleValue();
                                    flattenedRecord.putOpt("awayHandicap", -handicap);
                                } else {
                                    flattenedRecord.putOpt("awayHandicap", 0);
                                }
                            } else if ("OverUnder".equals(marketType)) {
                                // 大小盘处理
                                flattenedRecord.putOpt("homeHandicap", point);
                                flattenedRecord.putOpt("awayHandicap", point); // 大小盘两边盘口相同
                            }

                            // 解析价格信息
                            JSONArray prices = oddsRecord.getJSONArray("prices");
                            if (prices != null && prices.size() >= 2) {
                                for (int k = 0; k < prices.size(); k++) {
                                    JSONObject price = prices.getJSONObject(k);
                                    String option = price.getStr("option");
                                    Object oddsValue = price.get("price");

                                    if ("Handicap".equals(marketType) || "OverUnder".equals(marketType)) {
                                        // 让球盘和大小盘
                                        if ("h".equals(option)) {
                                            flattenedRecord.putOpt("homeOdds", oddsValue.toString());
                                            flattenedRecord.putOpt("homeWall", getWallType(oddsValue));
                                        } else if ("a".equals(option)) {
                                            flattenedRecord.putOpt("awayOdds", oddsValue.toString());
                                            flattenedRecord.putOpt("awayWall", getWallType(oddsValue));
                                        }
                                    }
                                }
                            }

                            // 添加排序权重字段
                            flattenedRecord.putOpt("sortWeight", getSortWeight(type, handicapType, point));
                            eventOddsList.add(flattenedRecord);
                        }

                        // 对该赛事的所有赔率进行排序
                        eventOddsList.sort(Comparator.comparingInt(o -> o.getInt("sortWeight")));

                        // 将排序后的赔率添加到最终结果中
                        for (JSONObject sortedRecord : eventOddsList) {
                            sortedRecord.remove("sortWeight"); // 移除临时排序字段
                            flattenedOddsArray.add(sortedRecord);
                            totalOddsCount++;
                        }
                    }
                }
            }

            log.info("数据结构平铺完成，共生成 {} 条赔率记录", totalOddsCount);

            // --- 构建最终成功的响应 ---
            finalResult.putOpt("success", true);
            finalResult.putOpt("code", 200);
            finalResult.putOpt("msg", "获取赛事赔率数据成功");
            finalResult.putOpt("totalCount", totalOddsCount);
            finalResult.putOpt("leagues", flattenedOddsArray); // 直接返回平铺后的赔率数组

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
        } finally {
            // 确保关闭线程池
            if (eventsExecutor != null) {
                PriorityTaskExecutor.shutdownExecutor(eventsExecutor);
            }
        }

        log.info("赛事列表处理完成，共 {} 场比赛", flattenedOddsArray.size());
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
