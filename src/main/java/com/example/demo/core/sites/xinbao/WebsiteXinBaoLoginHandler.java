package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.core.factory.ApiHandler;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.Map;

/**
 * 智博网站 - 登录 API具体实现
 */
@Component
public class WebsiteXinBaoLoginHandler implements ApiHandler {

    private static final String VER = "2025-01-03-removeBanner_69";

    private static final String LOGIN_URL = "https://m061.mos077.com/transform.php?ver=" + VER;

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
            throw new RuntimeException("Login failed with status code: " + response.getStatus());
        }

        // 解析响应
        Document docResult = XmlUtil.readXML(response.body());
        JSONObject responseJson = new JSONObject(response.body());
        responseJson.putOpt("token", XmlUtil.getByXPath("//serverresponse/uid", docResult, XPathConstants.STRING));
        return responseJson;
    }

    /**
     * 发送登录请求并返回结果
     * @param params 请求参数
     * @return 登录结果
     */
    @Override
    public JSONObject execute(JSONObject params) {
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 发送请求
        HttpResponse response = HttpRequest.post(LOGIN_URL)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应并返回
        return parseResponse(response);
    }
}
