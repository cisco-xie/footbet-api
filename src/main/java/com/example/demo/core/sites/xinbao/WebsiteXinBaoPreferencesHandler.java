package com.example.demo.core.sites.xinbao;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.core.factory.ApiHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 新二网站 - 偏好设置 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoPreferencesHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoPreferencesHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.add("Accept-Language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.add("Connection", "keep-alive");
        headers.add("Origin", "https://m061.mos077.com");
        headers.add("Referer", "https://m061.mos077.com/");
        headers.add("Sec-Fetch-Dest", "empty");
        headers.add("Sec-Fetch-Mode", "cors");
        headers.add("Sec-Fetch-Site", "same-origin");
        headers.add("sec-ch-ua", Constants.SEC_CH_UA);
        headers.add("User-Agent", Constants.USER_AGENT);
        headers.add("sec-ch-ua-mobile", "?0");
        headers.add("sec-ch-ua-platform", "\"Windows\"");

        JSONObject val = new JSONObject();
        val.putOpt("odd_f_type", params.getStr("oddsFormatType"));
        // 构造请求体
        String requestBody = String.format("p=memSet&uid=%s&ver=%s&langx=zh-cn&val=%s&action=send",
                params.getStr("uid"),
                Constants.VER,
                val
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
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        JSONObject responseJson = new JSONObject();
        // 解析响应
        if (!"1".equals(response.body())) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "偏好设置失败:"+response.body());
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("msg", "偏好设置成功");
        return responseJson;
    }

    /**
     * 发送请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "preferences");
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = HttpRequest.post(fullUrl)
                .addHeaders(request.getHeaders().toSingleValueMap())
                .body(request.getBody())
                .execute();

        // 解析响应并返回
        return parseResponse(params, response);
    }

    /**
     * 构建 cURL 命令
     * @param url
     * @param request
     * @return
     */
    private String buildCurlCommand(String url, HttpEntity<String> request) {
        StringBuilder curl = new StringBuilder("curl -X POST");

        // 添加 headers
        request.getHeaders().forEach((key, values) -> {
            for (String value : values) {
                curl.append(" -H '").append(key).append(": ").append(value).append("'");
            }
        });

        // 添加 body
        String body = request.getBody();
        if (StringUtils.isNotBlank(body)) {
            curl.append(" -d '").append(body.replace("'", "\\'")).append("'");
        }

        // 添加 URL
        curl.append(" '").append(url).append("'");

        return curl.toString();
    }

}
