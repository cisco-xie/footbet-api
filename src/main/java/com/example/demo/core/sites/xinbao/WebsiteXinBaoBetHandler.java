package com.example.demo.core.sites.xinbao;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 智博网站 - 投注 API具体实现
 * 请求参数f大概率代表投注来源,1M：大概率表示列表页下注  1R：大概率表示详情页下注  1O：大概率表示列表页中的展开信息下注。
 */
@Slf4j
@Component
public class WebsiteXinBaoBetHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoBetHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
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
//        String cookies = "b-user-id=85477736-98f0-11a4-4d7f-e21dbea69097; b-user-id=ODU0Nzc3MzYtOThmMC0xMWE0LTRkN2YtZTIxZGJlYTY5MDk3; odd_f_type_36826212=VFE9PQ==; box4pwd_notshow_36826212=MzY4MjYyMTJfTg==; CookieChk=WQ; iorChgSw=WQ==; myGameVer_36826212=XzIxMTIyOA==; box4pwd_notshow_37015545=MzcwMTU1NDVfTg==; myGameVer_37015545=XzIxMTIyOA==; box4pwd_notshow_37587241=Mzc1ODcyNDFfTg==; myGameVer_37587241=XzIxMTIyOA==; ft_myGame_37587241=e30=; lastBetCredit_sw_37587241=WQ==; lastBetCredit_37587241=NTA=; protocolstr=aHR0cHM=; test=aW5pdA; bk_myGame_37587241=e30=; login_37587241=MTc0NzIxMDAzNA; cu=Tg==; cuipv6=Tg==; ipv6=Tg==";
//        headers.add("cookie", cookies);

        String oddsFormatType = params.getStr("oddsFormatType");
        String golds = params.getStr("golds");
        String gid = params.getStr("gid");
        String gtype = params.getStr("gtype");
        String wtype = params.getStr("wtype");
        String rtype = params.getStr("rtype");
        String choseTeam = params.getStr("choseTeam");
        String ioratio = params.getStr("ioratio");
        String con = params.getStr("con");
        String ratio = params.getStr("ratio");
        String autoOdd = params.getStr("autoOdd");
        // 构造请求体
        String requestBody = String.format("p=FT_bet&uid=%s&ver=%s&langx=zh-cn&odd_f_type=%s&golds=%s&gid=%s&gtype=%s&wtype=%s&rtype=%s&chose_team=%s&ioratio=%s&con=%s&ratio=%s&autoOdd=%s" +
                        "&timestamp=%s&timestamp2=&isRB=Y&imp=N&ptype=&isYesterday=N&f=1M",
                params.getStr("uid"),
                Constants.VER,
                oddsFormatType,
                golds,
                gid,
                gtype,
                wtype,
                rtype,
                choseTeam,
                ioratio,
                con,
                ratio,
                autoOdd,
                System.currentTimeMillis()
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
        // 1. 检查响应状态码
        if (response.getStatus() != 200) {
            return new JSONObject()
                    .putOpt("code", response.getStatus())
                    .putOpt("success", false)
                    .putOpt("msg", "账户登录失效");
        }

        // 2. 获取响应内容
        String responseBody = response.body().trim();
        JSONObject responseJson;

        // 3. 判断是否为 JSON 格式
        try {
            responseJson = JSONUtil.parseObj(responseBody);
        } catch (Exception e) {
            log.error("[新2][投注失败][JSON解析异常][body={}]", responseBody, e);
            return new JSONObject()
                    .putOpt("betInfo", params.getJSONObject("betInfo"))
                    .putOpt("success", false)
                    .putOpt("msg", "投注返回格式错误（JSON 解析失败）");
        }

        // 4. 日志输出
        log.info("[新2][投注结果]{}", responseJson);

        // 5. 提取并验证投注结果
        JSONObject serverResponse = responseJson.getJSONObject("serverresponse");
        String code = serverResponse.getStr("code");
        String msg = serverResponse.getStr("msg");

        boolean isSuccess = "560".equals(code);
        return new JSONObject()
                .putOpt("success", isSuccess)
                .putOpt("betInfo", params.getJSONObject("betInfo"))
                .putOpt("msg", isSuccess ? "投注成功" : "投注失败: " + msg)
                .putOpt("data", responseJson);
    }

    /**
     * 发送投注请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(ConfigAccountVO userConfig, JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "bet");
        // 构建请求
        HttpEntity<String> requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 打印 cURL 格式请求
        // String curlCommand = buildCurlCommand(fullUrl, request);
        // log.info("即将发送请求:\n{}", curlCommand);

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
