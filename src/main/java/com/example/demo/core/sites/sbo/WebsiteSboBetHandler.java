package com.example.demo.core.sites.sbo;

import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.DebugGlobalLock;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 盛帆网站 - 投注 查看 API具体实现
 */
@Slf4j
@Component
public class WebsiteSboBetHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboBetHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("cache-control", "no-cache");
        headers.put("content-type", "application/json");
        headers.put("origin", params.getStr("referer"));
        headers.put("pragma", "no-cache");
        headers.put("priority", "u=1, i");
        headers.put("referer", params.getStr("referer")+"/");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("user-agent", Constants.USER_AGENT);

        // 固定的 SportsSession 值
        String FIXED_SPORTS_SESSION = "CfDJ8Ac1PWpfUt5Dj8g/R51yDE6DoVC68eAXYbWiFprdIdYXg1qopLXM9+3SfUiGUHQiy+ph7VUmHtO2PxNbObr9/9d42M8eXs3zXZBv57KR8GdX9D25eKTNqkEKbd8QqrD/eDdNTPE9q27cN4i55wpa1GCR/yEDDgA6OMz+KtHgrgFm";
        // 只提取 .SBO.SharedCookies.，SportsSession 使用固定值
        String fullCookie = params.getStr("cookie");
        String sharedCookie = extractSpecificCookies(fullCookie);
        // 组合固定的 SportsSession 和提取的 .SBO.SharedCookies.
        String filteredCookie = String.format("SportsSession=%s; %s", FIXED_SPORTS_SESSION, sharedCookie);
        headers.put("cookie", filteredCookie);

        return headers;
    }

    private String extractSpecificCookies(String fullCookie) {
        if (fullCookie == null || fullCookie.isEmpty()) {
            return "";
        }

        String[] cookies = fullCookie.split("; ");
        StringBuilder filteredCookies = new StringBuilder();

        for (String cookie : cookies) {
            if (cookie.startsWith(".SBO.SharedCookies.=")) {
                if (filteredCookies.length() > 0) {
                    filteredCookies.append("; ");
                }
                filteredCookies.append(cookie);
            }
        }

        return filteredCookies.toString();
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public String buildRequest(JSONObject params) {
        JSONObject requestBody = new JSONObject();
        requestBody.putOpt("betPage", 1);   // 滚球
        requestBody.putOpt("eventId", params.getInt("eventId"));
        requestBody.putOpt("liveScore", new JSONObject().putOpt("home", params.getInt("liveHomeScore")).putOpt("away", params.getInt("liveAwayScore")));  // 当前比分
        requestBody.putOpt("marketType", params.getInt("marketType"));
        requestBody.putOpt("oddsId", params.getInt("oddsId"));
        requestBody.putOpt("option", params.getStr("option"));  // 选择的队伍 h:主队 a:客队
        requestBody.putOpt("point", params.getBigDecimal("point"));    // 盘口让点
        requestBody.putOpt("sportType", 1);
        requestBody.putOpt("stake", params.getInt("stake"));    // 下注金额
        requestBody.putOpt("uid", params.getStr("uid"));        // 下注用户uid
        requestBody.putOpt("voucherIdString", "");

        return requestBody.toString();
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
            res.putOpt("betInfo", params.getJSONObject("betInfo"));
            res.putOpt("msg", "投注失败");
            return res;
        }

        // 解析响应
        JSONObject result = new JSONObject();
        JSONObject responseJson = new JSONObject(response.getBody());
        log.info("盛帆投注结果:{}", responseJson);
        if (!responseJson.getBool("isPlaceBetSuccess")) {
            log.info("盛帆投注失败-params={}-body={}", params, responseJson);
            result.putOpt("success", false);
            result.putOpt("betInfo", params.getJSONObject("betInfo"));
            result.putOpt("msg", "投注失败:"+responseJson.getStr("message"));
            return result;
        }
        result.putOpt("success", true);
        result.putOpt("data", responseJson);
        result.putOpt("betInfo", params.getJSONObject("betInfo"));
        result.putOpt("msg", "投注成功");
        return result;
    }

    /**
     * 发送投注请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "bet");
        // 构建请求
        String sportsBookUrl = insertSubdomain(baseUrl, "sportsbook");
        params.putOpt("referer", sportsBookUrl);
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s", sportsBookUrl, apiUrl);

        // 发送请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            log.info("盛帆投注发起请求, 用户:{}, 账号:{}, 请求头参数:{}, 请求参数:{}", username, userConfig.getAccount(), requestHeaders, requestBody);
            //DebugGlobalLock.enterDebugMode();
            // 全局锁检查（所有线程都会在这里等待）
            //DebugGlobalLock.waitIfDebugMode();
            resultHttp = dispatcher.executeFull("POST", fullUrl, requestBody, requestHeaders, userConfig);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }

    public static String insertSubdomain(String baseUrl, String subdomain) {
        URI uri = URI.create(baseUrl);
        String originalHost = uri.getHost();
        String host = originalHost;
        // 去掉 www.
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String newHost = subdomain + "." + host;
        return baseUrl.replaceFirst(originalHost, newHost);
    }
}
