package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.core.factory.ApiTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 登录 API具体实现
 */
@Component
public class WebsiteZhiBoLoginHandler implements ApiHandler {
    @Override
    public HttpEntity<String> buildRequest(Map<String, Object> params) {
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/json");
        headers.add("locale", "zh_CN");

        // 构造请求体
        JSONObject body = new JSONObject();
        body.putOpt("username", params.get("username"));
        body.putOpt("password", params.get("password"));

        return new HttpEntity<>(body.toString(), headers);
    }

    @Override
    public JSONObject parseResponse(String responseBody) {
        // 解析响应
        JSONObject responseJson = new JSONObject(responseBody);
        if (!responseJson.getBool("success", false)) {
        }
        return responseJson;
    }

    private static final String LOGIN_URL = "https://www.isn88.com/membersite-api/api/member/authenticate";

    /**
     * 发送登录请求
     * @param params 请求参数
     * @return 登录结果
     */
    @Override
    public JSONObject handleLogin(Map<String, Object> params) {
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 发送请求
        HttpResponse response = HttpRequest.post(LOGIN_URL)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 检查响应状态
        if (response.getStatus() != 200) {
        }

        // 解析响应
        return parseResponse(response.body());
    }
}
