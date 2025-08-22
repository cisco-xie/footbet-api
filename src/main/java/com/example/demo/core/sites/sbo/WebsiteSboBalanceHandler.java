package com.example.demo.core.sites.sbo;

import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SystemError;
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
 * 盛帆网站 - 账户额度 API具体实现
 */
@Slf4j
@Component
public class WebsiteSboBalanceHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboBalanceHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        // 构造请求头
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("content-type", "application/json");
        headers.put("cookie", params.getStr("cookie"));

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
        return "";
    }

    /**
     * 解析响应体
     * @param result 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult result) {

        // 检查响应状态
        if (result == null || result.getStatus() != 200) {
            JSONObject res = new JSONObject();
            int status = result != null ? result.getStatus() : -1;
            res.putOpt("code", status);
            res.putOpt("success", false);
            res.putOpt("msg", status == 403 ? "账户登录失效" : "获取账户额度失败");
            return res;
        }
        // 解析响应
        JSONObject responseJson = new JSONObject(result.getBody());

        // 如果响应中包含错误信息，抛出异常或者其他处理
        if (responseJson.isEmpty()) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取账户额度失败");
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("durationMs", result.getDurationMs());
        responseJson.putOpt("msg", "获取账户额度成功");
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
        String apiUrl = apiUrlService.getApiUrl(siteId, "info");
        String apiHomeUrl = insertSubdomain(baseUrl, "api-home");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s", apiHomeUrl, apiUrl);
        // 使用代理发起请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("POST", fullUrl, requestBody, requestHeaders, userConfig, false);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }

    public static String insertSubdomain(String baseUrl, String subdomain) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();  // u16888.com
        String newHost = subdomain + "." + host;
        return baseUrl.replaceFirst(host, newHost);
    }
}
