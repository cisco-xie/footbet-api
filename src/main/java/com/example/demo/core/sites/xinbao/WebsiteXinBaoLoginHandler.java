package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.factory.ApiHandler;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.Map;
import java.util.Objects;

/**
 * 智博网站 - 登录 API具体实现
 */
@Component
public class WebsiteXinBaoLoginHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoLoginHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    private static final String VER = "2025-01-03-removeBanner_69";

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public HttpEntity<String> buildRequest(JSONObject params) {
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/x-www-form-urlencoded");

        // 构造请求体
        String requestBody = String.format("p=chk_login&langx=zh-cn&ver=2024-12-24-197_65&username=%s&password=%s&app=N&auto=CDDFZD&blackbox=",
                params.getStr("username"),
                params.getStr("password")
        );

        return new HttpEntity<>(requestBody, headers);
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(HttpResponse response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("success", false);
                res.putOpt("msg", "账户登录失败");
                return res;
            }
            res.putOpt("code", 403);
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失败");
            return res;
        }

        // 解析响应
        Document docResult = XmlUtil.readXML(response.body());
        JSONObject responseJson = new JSONObject(response.body());
        Object token = XmlUtil.getByXPath("//serverresponse/uid", docResult, XPathConstants.STRING);
        if (ObjectUtil.isEmpty(token)) {
            responseJson.putOpt("msg", "账户登录失败");
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("token", token);
        responseJson.putOpt("msg", "账户登录成功");
        return responseJson;
    }

    /**
     * 发送登录请求并返回结果
     * @param params 请求参数
     * @return 登录结果
     */
    @Override
    public JSONObject execute(JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "login");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = HttpRequest.post(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应并返回
        return parseResponse(response);
    }
}
