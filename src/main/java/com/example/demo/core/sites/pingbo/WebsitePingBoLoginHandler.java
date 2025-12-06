package com.example.demo.core.sites.pingbo;

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

import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 登录 API具体实现
 */
@Slf4j
@Component
public class WebsitePingBoLoginHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoLoginHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        return String.format("loginId=%s&password=%s&captcha=&captchaToken=",
                params.getStr("loginId"),
                params.getStr("password")
        );
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
            res.putOpt("msg", status == 403 ? "账户登录失败" : "协议请求失败");
            return res;
        }
        // 解析响应
        JSONObject responseJson;
        try {
            responseJson = new JSONObject(result.getBody());

            log.info("平博登录结果: {}", responseJson);
            // 如果响应中包含错误信息，抛出异常或者其他处理
            if (responseJson.getInt("code") != 1) {
                if (responseJson.getInt("code") == 2) {
                    // 首次登录需要修改密码
                    responseJson.putOpt("code", 106);
                    responseJson.putOpt("success", false);
                    responseJson.putOpt("msg", "首次登录需要修改密码");
                    return responseJson;
                } else if (responseJson.getInt("code") == 3) {
                    // 需要同意协议
                    responseJson.putOpt("code", 110);
                    responseJson.putOpt("success", false);
                    responseJson.putOpt("msg", "需接受账户协议");
                    return responseJson;
                } else if (responseJson.getInt("code") == -4) {
                    // 需要同意协议
                    responseJson.putOpt("code", -4);
                    responseJson.putOpt("success", false);
                    responseJson.putOpt("msg", "您的账户已被暂停使用，请联系您的上线寻求帮助。");
                    return responseJson;
                }
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "账户登录失败");
                return responseJson;
            }
        } catch (Exception e) {
            responseJson = new JSONObject();
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失败");
            return responseJson;
        }

        responseJson.getJSONObject("tokens").putOpt("x-app-data", result.getHeaders().get("x-app-data").toString().replace("[", "").replace("]", ""));
        responseJson.putOpt("success", true);
        responseJson.putOpt("msg", "账户登录成功");
        return responseJson;
    }

    /**
     * 发送登录请求并返回结果
     * @param params 请求参数
     * @return 登录结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "login");
        // 构建请求
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("locale=zh_CN&_=%s&withCredentials=true",
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

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
}
