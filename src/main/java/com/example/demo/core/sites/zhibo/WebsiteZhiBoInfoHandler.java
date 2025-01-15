package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.core.factory.ApiHandler;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 智博网站 - 获取账号信息 API具体实现
 */
@Component
public class WebsiteZhiBoInfoHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteZhiBoInfoHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    @Override
    public HttpEntity<String> buildRequest(JSONObject params) {
        // 构造请求头
        HttpHeaders headers = new HttpHeaders();
        headers.add("accept", "*/*");
        headers.add("content-type", "application/json");
        headers.add("locale", "zh_CN");
        headers.add("authorization", params.getStr("token"));

        return new HttpEntity<>(headers);
    }

    @Override
    public JSONObject parseResponse(HttpResponse response) {

        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("success", false);
                res.putOpt("msg", "账户登录失效");
                return res;
            }
        }
        // 解析响应
        JSONObject responseJson = new JSONObject(response.body());
        if (!responseJson.getBool("success", false)) {
        }
        responseJson.putOpt("msg", "获取账户额度成功");
        return responseJson;
    }

    /**
     * 发送登录请求
     * @param params 请求参数
     * @return 登录结果
     */
    @Override
    public JSONObject execute(JSONObject params) {

        // 获取 完整API 路径
        String username = params.getStr("username");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "info");

        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 发送请求
        HttpResponse response = HttpRequest.get(baseUrl+apiUrl+"?_=" + System.currentTimeMillis())
                .addHeaders(request.getHeaders().toSingleValueMap())
                .execute();

        // 解析响应
        return parseResponse(response);
    }
}
