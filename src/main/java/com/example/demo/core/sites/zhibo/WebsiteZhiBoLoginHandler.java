package com.example.demo.core.sites.zhibo;

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

/**
 * 智博网站 - 登录 API具体实现
 */
@Component
public class WebsiteZhiBoLoginHandler implements ApiHandler {
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteZhiBoLoginHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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

        // 构造请求体
        JSONObject body = new JSONObject();
        body.putOpt("username", params.getStr("username"));
        body.putOpt("password", params.getStr("password"));

        return new HttpEntity<>(body.toString(), headers);
    }

    @Override
    public JSONObject parseResponse(JSONObject params, HttpResponse response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("msg", "账户登录失效");
                return res;
            }
            res.putOpt("msg", "账户登录失效");
            return res;
        }
        // 解析响应
        JSONObject responseJson = new JSONObject(response.body());
        if (!responseJson.getBool("success", false)) {
            responseJson.putOpt("code", response.getStatus());
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失败");
            return responseJson;
        }
        if (responseJson.containsKey("nextAction") && !responseJson.getStr("nextAction").isBlank()) {
            if (responseJson.getStr("nextAction").equals("member/changePassword")) {
                // 需要修改密码
                responseJson.putOpt("code", 106);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要修改密码");
            } else if (responseJson.getStr("nextAction").equals("member/acceptAgreement")) {
                // 需要同意协议
                responseJson.putOpt("code", 110);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要接受账户协议");
            } else if (responseJson.getStr("nextAction").equals("member/setLoginName")) {
                // 需要修改用户登录名
                responseJson.putOpt("code", 109);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要修改昵称");
            } else if (responseJson.getStr("nextAction").equals("member/updatePreferences")) {
                // 需要保存偏好
                responseJson.putOpt("code", 111);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要保存偏好");
            }
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("msg", "账户登录成功");
        return responseJson;
    }

    /**
     * 发送登录请求
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

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s", baseUrl, apiUrl);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.post(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap())
                .body(requestBody.getBody())
                .timeout(5000);
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应
        return parseResponse(params, response);
    }
}
