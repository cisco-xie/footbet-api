package com.example.demo.core.sites.pingbo;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 智博网站 - 登录 API具体实现
 */
@Component
public class WebsitePingBoLoginHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsitePingBoLoginHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

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
        String requestBody = String.format("loginId=%s&password=%s&captcha=&captchaToken=",
                params.getStr("loginId"),
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
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {

        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("success", false);
                res.putOpt("msg", "账户登录失败");
                return res;
            }
            res.putOpt("code", response.getStatus());
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失败");
            return res;
        }
        // 解析响应
        JSONObject responseJson = new JSONObject(response.body());

        // 如果响应中包含错误信息，抛出异常或者其他处理
        if (responseJson.getInt("code") != 1) {
            if (responseJson.getInt("code") == 2) {
                // 首次登录需要修改密码
                responseJson.putOpt("code", 106);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "账户昵称或密码错误");
                return responseJson;
            } else if (responseJson.getInt("code") == 3) {
                // 需要同意协议
                responseJson.putOpt("code", 110);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需接受账户协议");
                return responseJson;
            }
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失败");
            return responseJson;
        }
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
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("locale=zh_CN&_=%s&withCredentials=true",
                System.currentTimeMillis()
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.post(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap())
                .body(requestBody.getBody())
                .timeout(5000);
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应并返回
        return parseResponse(params, response);
    }
}
