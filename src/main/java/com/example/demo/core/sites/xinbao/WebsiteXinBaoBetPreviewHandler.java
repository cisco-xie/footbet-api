package com.example.demo.core.sites.xinbao;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 新二网站 - 投注预览 查看 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoBetPreviewHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoBetPreviewHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
        headers.add("Sec-Fetch-Dest", "empty");
        headers.add("Sec-Fetch-Mode", "cors");
        headers.add("Sec-Fetch-Site", "same-origin");
        headers.add("sec-ch-ua", Constants.SEC_CH_UA);
        headers.add("User-Agent", Constants.USER_AGENT);
        headers.add("sec-ch-ua-mobile", "?0");
        headers.add("sec-ch-ua-platform", "\"Windows\"");

        String oddsFormatType = params.getStr("oddsFormatType");
        String gid = params.getStr("gid");
        String gtype = params.getStr("gtype");
        String wtype = params.getStr("wtype");
        String choseTeam = params.getStr("choseTeam");
        // 构造请求体
        String requestBody = String.format("p=FT_order_view&uid=%s&ver=%s&langx=zh-cn&odd_f_type=%s&gid=%s&gtype=%s&wtype=%s&chose_team=%s",
                params.getStr("uid"),
                Constants.VER,
                oddsFormatType,
                gid,
                gtype,
                wtype,
                choseTeam
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

        // 解析响应
        JSONObject responseJson = new JSONObject(response.body());
        log.info("[新2][投注预览]{}", responseJson);
        JSONObject serverresponse = responseJson.getJSONObject("serverresponse");
        if (!"501".equals(serverresponse.getStr("code"))) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "投注预览失败:"+serverresponse.getStr("msg"));
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("data", responseJson);
        responseJson.putOpt("msg", "投注预览成功");
        return responseJson;
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
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        HttpResponse response = null;
        HttpRequest request = HttpRequest.post(fullUrl)
                .addHeaders(requestBody.getHeaders().toSingleValueMap())
                .body(requestBody.getBody());
        // 引入配置代理
        HttpProxyConfig.configureProxy(request, userConfig);
        response = request.execute();

        // 解析响应并返回
        return parseResponse(params, response);
    }

}
