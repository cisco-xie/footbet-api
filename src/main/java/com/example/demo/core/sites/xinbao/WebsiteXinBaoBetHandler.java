package com.example.demo.core.sites.xinbao;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.core.factory.ApiHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 智博网站 - 投注 API具体实现
 * 请求参数f大概率代表投注来源,1M：大概率表示手机网页下注  1R：大概率表示新版自适应网页下注。
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
        headers.add("sec-ch-ua", Constants.SEC_CH_UA);
        headers.add("user-agent", Constants.USER_AGENT);
        headers.add("sec-ch-ua-platform", "\"Windows\"");
        String cookies = "b-user-id=85477736-98f0-11a4-4d7f-e21dbea69097; b-user-id=ODU0Nzc3MzYtOThmMC0xMWE0LTRkN2YtZTIxZGJlYTY5MDk3; odd_f_type_36826212=VFE9PQ==; box4pwd_notshow_36826212=MzY4MjYyMTJfTg==; CookieChk=WQ; iorChgSw=WQ==; myGameVer_36826212=XzIxMTIyOA==; box4pwd_notshow_37015545=MzcwMTU1NDVfTg==; myGameVer_37015545=XzIxMTIyOA==; box4pwd_notshow_37587241=Mzc1ODcyNDFfTg==; myGameVer_37587241=XzIxMTIyOA==; ft_myGame_37587241=e30=; lastBetCredit_sw_37587241=WQ==; lastBetCredit_37587241=NTA=; protocolstr=aHR0cHM=; test=aW5pdA; bk_myGame_37587241=e30=; login_37587241=MTc0NzEzNDk0OA; cu=Tg==; cuipv6=Tg==; ipv6=Tg==";
        headers.add("cookie", cookies);

        String oddFType = params.getStr("oddFType");
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
                        "&timestamp=%s&timestamp2=&isRB=Y&imp=N&ptype=&isYesterday=N&f=1R",
                params.getStr("uid"),
                Constants.VER,
                oddFType,
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
    public JSONObject parseResponse(HttpResponse response) {
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            res.putOpt("msg", "账户登录失效");
            return res;
        }

        // 解析响应
        JSONObject responseJson = new JSONObject(response.body());
        log.info("[新2][投注]{}", responseJson);
        JSONObject serverresponse = responseJson.getJSONObject("serverresponse");
        if (!"560".equals(serverresponse.getStr("code"))) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "投注失败:"+serverresponse.getStr("msg"));
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("data", responseJson);
        responseJson.putOpt("msg", "投注成功");
        return responseJson;
    }

    /**
     * 发送投注请求并返回结果
     * @param params 请求参数
     * @return 结果
     */
    @Override
    public JSONObject execute(JSONObject params) {
        // 获取 完整API 路径
        String username = params.getStr("adminUsername");
        String siteId = params.getStr("websiteId");
        String baseUrl = websiteService.getWebsiteBaseUrl(username, siteId);
        String apiUrl = apiUrlService.getApiUrl(siteId, "unsettled");
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
        return parseResponse(response);
    }
}
