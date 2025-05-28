package com.example.demo.core.sites.zhibo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 智博网站 - 账户额度 API具体实现
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
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {

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
        /*if (!responseJson.getBool("success", false)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "获取账户额度失败");
            return responseJson;
        }*/
        responseJson.putOpt("success", true);
        responseJson.putOpt("msg", "获取账户额度成功");
        return responseJson;
    }

    /**
     * 发送账户额度请求
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

        // 构建请求
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("_=%s",
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.get(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap());
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应
        return parseResponse(params, response);
    }
}
