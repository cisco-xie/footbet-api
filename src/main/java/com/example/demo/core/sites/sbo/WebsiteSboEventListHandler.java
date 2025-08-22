package com.example.demo.core.sites.sbo;

import cn.hutool.core.net.URLEncodeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.SboCdnApiConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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
        JSONArray matchesWithFullData = new JSONArray();

        try {
            // --- 第一步：获取赛事基本列表 (您原有的逻辑) ---
            log.info("开始执行第一步：获取赛事基本列表");
            String presetFilter = "Live";
            String date = "All"; // 或者 "Total"
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
                // 第一步失败直接返回错误
                return step1Response;
            }
            JSONArray eventList = step1Response.getJSONObject("data").getJSONArray("events");
            log.info("第一步成功，获取到 {} 场赛事", eventList.size());

            // --- 第二步：获取实时比分 ---
            JSONObject step2Response = step2GetLiveScores(userConfig, params, requestHeaders, presetFilter, date);
            JSONArray liveScoresList = step2Response.getJSONObject("data").getJSONArray("events");
            log.info("第二步成功，获取到 {} 场赛事的比分", liveScoresList.size());

            // 创建一个Map便于根据ID查找比分信息，避免后续嵌套循环
            Map<Long, JSONObject> liveScoreMap = new HashMap<>();
            for (int i = 0; i < liveScoresList.size(); i++) {
                JSONObject scoreEvent = liveScoresList.getJSONObject(i);
                liveScoreMap.put(scoreEvent.getLong("id"), scoreEvent);
            }

            // --- 第三步：遍历赛事列表，获取每场的赔率 ---
            log.info("开始执行第三步：逐个获取赛事赔率 (共 {} 场)...", eventList.size());
            for (int i = 0; i < eventList.size(); i++) {
                JSONObject basicEventInfo = eventList.getJSONObject(i);
                Long eventId = basicEventInfo.getLong("id");

                // 1. 合并基础信息和比分信息
                JSONObject combinedEventData = basicEventInfo.clone();
                JSONObject scoreInfo = liveScoreMap.get(eventId);
                if (scoreInfo != null) {
                    combinedEventData.putOpt("scoreInfo", scoreInfo);
                } else {
                    combinedEventData.putOpt("scoreInfo", JSONUtil.createObj().putOpt("error", "未找到比分数据"));
                }

                // 2. 获取该赛事的赔率
                JSONObject oddsInfo = step3GetOddsForEvent(userConfig, params, requestHeaders, eventId, presetFilter);
                combinedEventData.putOpt("oddsInfo", oddsInfo);

                // 3. 将整合好的数据加入最终列表
                matchesWithFullData.add(combinedEventData);

                // 避免请求过快，添加延迟 (根据API限制调整)
                // try { Thread.sleep(100); } catch (InterruptedException e) { ... }
            }
            log.info("第三步完成");

            // --- 构建最终成功的响应 ---
            finalResult.putOpt("success", true);
            finalResult.putOpt("code", 200);
            finalResult.putOpt("msg", "获取赛事完整数据成功");
            // 将原始第一步的data结构保留，但用新的完整数据替换events
            JSONObject dataObj = step1Response.getJSONObject("data").clone();
            dataObj.putOpt("events", matchesWithFullData);
            finalResult.putOpt("data", dataObj);

        } catch (BusinessException e) {
            // 处理已知的业务异常
            log.error("业务流程异常: {}", e.getMessage(), e);
            finalResult.putOpt("success", false);
            finalResult.putOpt("code", 400);
            finalResult.putOpt("msg", e.getMessage());
        } catch (Exception e) {
            // 处理其他未知异常
            log.error("执行完整流程未知异常: {}", e.getMessage(), e);
            finalResult.putOpt("success", false);
            finalResult.putOpt("code", 500);
            finalResult.putOpt("msg", "系统内部错误，获取赛事数据失败");
        }

        log.info("赛事列表:{}", finalResult);
        return finalResult;
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
