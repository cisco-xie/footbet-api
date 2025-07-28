package com.example.demo.core.sites.xinbao;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 账户额度 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoBetUnsettledHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoBetUnsettledHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        return String.format("p=get_today_wagers&uid=%s&ver=%s&langx=zh-cn&LS=g&selGtype=ALL&chk_cw=N&ts=%s&format=json&db_slow=N",
                params.getStr("uid"),
                Constants.VER,
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
        JSONArray wagers = responseJson.getJSONArray("wagers");
        if (wagers == null || wagers.isEmpty()) {
            responseJson.putOpt("success", true);
            responseJson.putOpt("data", null);
            responseJson.putOpt("msg", "获取账户投注历史成功");
            return responseJson;
        }
        log.info("原始未结单列表数据:{}", responseJson);
        wagers.forEach(json -> {
            JSONObject wager = new JSONObject();
            JSONObject wagerJson = (JSONObject) json;
            wager.putOpt("betId", wagerJson.getStr("w_id"));    // 注单ID
            wager.putOpt("product", wagerJson.getStr("gtype")+wagerJson.getStr("wtype")+"("+wagerJson.getStr("score")+")");  // 体育+盘口类型+比分
            wager.putOpt("league", wagerJson.getStr("league")); // 联赛名称
            wager.putOpt("team", wagerJson.getStr("team_h_show") + " -vs- "+wagerJson.getStr("team_c_show"));    // 主队 -vs- 客队
            wager.putOpt("odds", wagerJson.getStr("result") + " @ " + wagerJson.getStr("ioratio"));  // 投注选项 + 赔率
            wager.putOpt("oddsValue", wagerJson.getStr("ioratio"));  // 赔率
            wager.putOpt("oddsTypeName", wagerJson.getStr("oddf_type")); // 盘口类型（如：香港盘）
            wager.putOpt("detail", wagerJson.getStr("league") + " - " + wagerJson.getStr("team_h_show") + " v "+wagerJson.getStr("team_c_show"));   // 详情
            wager.putOpt("result", wagerJson.getStr("result")); // 投注选项（例如 大 2.5、小盘、韩国）
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
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
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
