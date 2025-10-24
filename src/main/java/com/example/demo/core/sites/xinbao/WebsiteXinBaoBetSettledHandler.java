package com.example.demo.core.sites.xinbao;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 新二网站 - 投注历史 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoBetSettledHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoBetSettledHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        // 构造请求体
        return String.format("p=history_switch&uid=%s&langx=zh-cn&LS=c&today_gmt=%s&gtype=ALL&tmp_flag=Y",
                params.getStr("uid"),
                params.getStr("date")
        );
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        JSONArray result = new JSONArray();
        JSONObject responseJson = new JSONObject(response.getBody());
        log.info("原始已结单列表数据:{}", responseJson);
        JSONObject serverResponse = responseJson.getJSONObject("serverresponse");
        // 先安全获取原始值（可能为 JSONArray / JSONObject / String / null）
        Object wagersObj = serverResponse == null ? null : serverResponse.get("wagers");
        JSONArray wagers;
        if (wagersObj instanceof JSONArray) {
            // 正常数组
            wagers = (JSONArray) wagersObj;
        } else if (wagersObj instanceof JSONObject) {
            // 单个对象，包装成数组处理
            wagers = new JSONArray();
            wagers.add(wagersObj);
        } else if (wagersObj instanceof String) {
            // 有些接口会把 JSON 当字符串返回，尝试解析
            String s = (String) wagersObj;
            s = s.trim();
            if (s.startsWith("[")) {
                wagers = JSONUtil.parseArray(s);
            } else if (s.startsWith("{")) {
                wagers = new JSONArray();
                wagers.add(JSONUtil.parseObj(s));
            } else {
                // 既不是数组也不是对象的字符串，置空数组
                wagers = new JSONArray();
            }
        } else {
            // null 或未知类型 -> 视为空数组
            wagers = new JSONArray();
        }
        log.info("原始已结单列表wagers数据:{}", wagers);
        if (wagers.isEmpty()) {
            responseJson.putOpt("success", true);
            responseJson.putOpt("data", null);
            responseJson.putOpt("msg", "获取账户投注历史成功");
            return responseJson;
        }
        wagers.forEach(json -> {
            JSONObject wager = new JSONObject();
            JSONObject wagerJson = (JSONObject) json;
            String home = wagerJson.getStr("team_h_show");
            String away = wagerJson.getStr("team_c_show");
            String strong = wagerJson.getStr("strong"); // "Y" 主队让球, "N" 客队让球
            String resultTeam = wagerJson.getStr("result"); // 用户投注/结果的队名
            String rawHomeRatio = wagerJson.getStr("team_h_ratio");
            String rawAwayRatio = wagerJson.getStr("team_c_ratio");
            // 计算谁是让球方（Y = 显示为负号，N = 显示为正号）
            boolean homeIsGiving = "Y".equalsIgnoreCase(strong); // Y: 负盘

            String prefix = homeIsGiving ? "-" : "+";
            String ratio = "";
            if ("(滚球) 让球".equals(wagerJson.getStr("wtype"))) {
                if (StringUtils.isNotBlank(rawHomeRatio)) {
                    ratio = prefix + rawHomeRatio + " ";
                } else {
                    ratio = prefix + rawAwayRatio + " ";
                }
            }
            wager.putOpt("betId", wagerJson.getStr("w_id"));    // 注单ID
            wager.putOpt("product", wagerJson.getStr("gtype")+wagerJson.getStr("wtype")+"("+wagerJson.getStr("score")+")");  // 体育+盘口类型+比分
            wager.putOpt("league", wagerJson.getStr("league")); // 联赛名称
            wager.putOpt("team", home + " -vs- "+ away);    // 主队 -vs- 客队
            wager.putOpt("odds", resultTeam + " " + ratio + "@ " + wagerJson.getStr("ioratio"));  // 投注选项 + 赔率
            wager.putOpt("oddsValue", wagerJson.getStr("ioratio"));  // 赔率
            wager.putOpt("oddsTypeName", wagerJson.getStr("oddf_type")); // 盘口类型（如：香港盘）
            wager.putOpt("detail", wagerJson.getStr("league") + " - " + home + " -vs- "+ away);   // 详情
            wager.putOpt("result", resultTeam); // 投注选项（例如 大 2.5、小盘、韩国）
            wager.putOpt("amount", wagerJson.getStr("gold"));   // 投注金额
            wager.putOpt("win", wagerJson.getStr("win_gold")); // 可赢金额
            wager.putOpt("status", wagerJson.getStr("ball_act_ret")); // 状态（例如：确认）
            wager.putOpt("betTime", wagerJson.getStr("addtime")); // 投注时间
            wager.putOpt("gtype", wagerJson.getStr("gtype")); // 游戏类型，足球
            wager.putOpt("handicapType", wagerJson.getStr("wtype")); // 投注类型，例如：(滚球) 让球
            wager.putOpt("betType", wagerJson.getStr("bet_wtype")); // 投注玩法代码（如：ROU、RE）
            wager.putOpt("showType", wagerJson.getStr("showtype")); // 是否滚球盘：live 为滚球

            result.add(wager);
        });
        responseJson.putOpt("success", true);
        responseJson.putOpt("data", result);
        responseJson.putOpt("msg", "获取账户投注历史成功");
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
        String apiUrl = apiUrlService.getApiUrl(siteId, "settled");
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
}
