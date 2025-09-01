package com.example.demo.core.sites.sbo;

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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 盛帆网站 - 投注预览 查看 API具体实现
 */
@Slf4j
@Component
public class WebsiteSboBetPreviewHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteSboBetPreviewHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.put("accept-language", "zh-CN,zh;q=0.9");
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
        JSONObject requestBody = new JSONObject();
        requestBody.putOpt("eventId", params.getInt("eventId"));
        requestBody.putOpt("isLive", true);                         // 默认滚球
        requestBody.putOpt("marketType", params.getInt("marketType"));
        requestBody.putOpt("oddsId", params.getInt("oddsId"));
        requestBody.putOpt("option", params.getStr("option"));  // 选择的队伍 h:主队 a:客队
        requestBody.putOpt("sportType", 1);

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
            res.putOpt("msg", "投注预览失败");
            return res;
        }

        // 解析响应
        JSONObject result = new JSONObject();
        JSONObject responseJson = new JSONObject(response.getBody());
        log.info("[盛帆][投注预览]{}", responseJson);
        if (responseJson.getInt("errorCode") != 0) {
            log.info("[盛帆][投注预览失败][params={}][body={}]", params, responseJson);
            result.putOpt("success", false);
            result.putOpt("msg", "投注预览失败");
            return result;
        }
        result.putOpt("success", true);
        result.putOpt("data", responseJson);
        result.putOpt("msg", "投注预览成功");
        return result;
    }

    /**
     * 发送投注预览请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "betPreview");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);
        String sportsBookUrl = insertSubdomain(baseUrl, "sportsbook");

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s", sportsBookUrl, apiUrl);

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

    public static String insertSubdomain(String baseUrl, String subdomain) {
        URI uri = URI.create(baseUrl);
        String host = uri.getHost();  // u16888.com
        String newHost = subdomain + "." + host;
        return baseUrl.replaceFirst(host, newHost);
    }
}
