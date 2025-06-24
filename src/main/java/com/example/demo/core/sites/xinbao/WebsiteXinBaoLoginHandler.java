package com.example.demo.core.sites.xinbao;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 智博网站 - 登录 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoLoginHandler implements ApiHandler {

    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoLoginHandler(WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    private final String blackbox = "0400/2TxmNBLO3jjK9GFecOQi1PuF1jIHrmFefjjbHqNoxl9KNCsnZriRZkrH6/2kAD4IHKnG6orFsjozajRFNJMrebHa83CDZB6Z9eq6YfeVeibaEJk6505RqeeQF6QSFWk0p@rgQSgvNSaUdD@CBS5IMCS4zYqJYi4px285qKQ3aopQs/EGzmZL3Rd4FvZ4qbAV6by8@VZ1Pxqu@3DzXXI1mtGMuSZx2KTU@4XWMgeuYV5@ONseo2jGX0o0KydmuJFmSsfr/aQAPggcqcbqisWyOjNqNEU0kytwKr0Mf@ogWarmN0g8biR9jCRXeHWMGR4eZoaylFyE@Lap@4LwTdYTg3uK@EtNp0ExIoQRH3eoZj4vH6bbk@Ffuey55rD9/B2LfwwDdaHVagmBVKTSlwpRMIMmxLnSA@WqgxBwcdDGoKjAMddvFicdvWUj9rtr4VPkrboGV03a9KZ47nYJC0gMxLXrw6h637KjNXsgMEghG2DHm3gJbBd7I8GTqBFRT2mEU387Lj8zVyCuJoygDKlC7I@vS3zO1UWKyNRXh0mw7Kb1x4cMpEKIrjEjVFMhgLyi4PvWoIpyZmpr5zlQx90s1eovP7W@WpQCv10TaNx@XTCCuhAF@nK4s1qEvK5iTwAAOXDaU/lbtpUgBbu4NCNcyw@Jld00Kp8oBvPA6u04i1McDaVptovbQIefqQt8rKUfU5IRUUyQJwmINsJ9VlMYGTX23I2zuDzBDFLHM@5G3JxvQfkiMeQJwpKzgpVgoHiu5HF2/ZEGPjUHhsAngeC3nTcpiE5xELlJS9wzsAJdIZc6njJgQh/4mHk3C7XHo8gOwOwd8366BmBGA35nwcAWEYXN3@bqydWC1ydTmkV6SIHyf83JPkBAkRaH4lVulA8gRgN@Z8HAFhGFzd/m6snVgtcnU5pFekiB8n/NyT5AQJEWh@JVbpQPIEYDfmfBwBYRhc3f5urJ1a2nQ9gdLOQdkFS9nbsv6@gKK3K7azAG9qciRLc3gjswhNBM7WaTBsii85yJSPYWZuvlcetxenoDd7uW4fKrTjGYSqEk9HYTgjgklNKGT1jRHxfYV3WeA0UPPK1E1@GGd9pLLGVQUKIYR2wTw53CiWK/XQXiHK5P5nW8k6kOYliBrI@vS3zO1UWKyNRXh0mw7Kb1x4cMpEKIrjEjVFMhgLyi4PvWoIpyZmpr5zlQx90s1eovP7W@WpQCv10TaNx@XTCCuhAF@nK4s1qEvK5iTwAAOXDaU/lbtpUgBbu4NCNc1uhOU@0o207qgQnRelHMpZVJUiLP0GDKtOysBq9KkTa0JKsuZ2fFcZrrYcw9a0Q@QPW7nOa6BsYXviecWwHDvK73G35KiYW@F4pAJzL3lbHSPVI2CjVrMYCOs6dhiFRbKM6PKefd59YQpD/Z1ruUzGnl2/sDEJChPoMuBVEQHp9oYoLl/@64DTmItwc5qAXHrMc/hl2WkFqFbyE16iif3aOh9ltHZfQItCLg8OWNAZNnHJNjNZFAIhtMAxUoiB4Pzz3/NLRXCrZ3mfjBqE9jtZutL3qOhvyx9W0/wjb0rPf1tq/rRc5LTFZ54xEBtPibVRbjTohC3He@o0tCDnp9AlFhJKyMgZypm8czElJjtdFYpt24Quva42pJ7c1G6KgbMryTGU8rqWd/95C208iJlKkoS/GmX2WA3vDOtoiPGaDSdYogTGekWE/1vPmh5kD1MvupUNR22X3hscA83KegEx1xfl2g/QhbR0mN5PBb1gcCRx2SJGW@bkCpKJvx0bHbqYiJ/@IO4wJSkZn1KQSTGVHMYdApGHZV1rWB@vxYqVK6UngI6Mg57URPPSbAY2Nff7nHxrLhQUL/yafbkTHorGlRFol2zh9gT3oJtsWrmejN1e5qfQ5tiYN6SaJ4Ow9vlN5w0SKKGrcpK2Vei4VyLAzid5@atrDI/34FquG3it8Wnve8bAaq2pBttmXRMyL2bcCcl6Xpyx@Qgi4u5BaMI3EJh8lSgn0icxN0ibZil8ZSAAfaKZf2XkNyy1dtsk6QeWcOSdRTB/N7zE@L5mAC9ArrPR7XnTzk0qbZ4gsUOwUgzBv7b5PhChIXPVuwNTxpxbKEKeL1j0KflXms3M9ezSvjHn0Z6/W/0z2oY@d@AnEvmL@jmcjrxY1/5qservcqlixm@2BzG01KJjn/0A5LdwRR7dm3B/IMB6nVl6m/sFmuJ/kB3Rz162pcc1vkwiBX7BZ8LAZmOuioQnBJQSayYLiR5NrkN/50lvzXuuqObWoU/NlEbqiJbEoIYoBWvwrUX/JaIxitilnqC3mBOXJVZvm2b/DCsqt0BrkE/6GqlcV3wSdoPCgCI9NvcMymRBrtlxDozTBhjvHa91MAkdtteeCdaq6XynUPY5wYYX3ZA61H6CVL74w7Zq1t@vuXvjGgi92Nf/c54V@S1DR2v154RPl3OCiMt8Ua9NZsaZE@BQkQbbZl0TMi9m2zPZQ9LQV2EIIuLuQWjCNxCYfJUoJ9InmphqlCQ2N4EgAH2imX9l5DcstXbbJOkG1DR3uAoWzm71NDVhzhQvxFNDjMN6C39jmlz6jSwYUXZhbhE8Hje2cJh/7x78tebi5qWPXY1BCHmrSH/qPqQUGCdEecYEf8ciyNBvQvEOVqBkQaRnF5NATmVdDCHLNRE7Q0MDN@zUCXkqUiElSuc6hPz0bJFNdZx9@aPebjJ6jw276OFOInzDdB0yLg4Ni8RIhHenn6igzlVuuinruxU8fj0tWgH/SNS4HD9rIagnVGFK7gw4SK6YEToIogbLa1fJR8mAmEQ2K@kO9@DXZe231IkyKqUL/rVV8uKhoo@iKAZrtY0htOjpdvjqLfp2dEsuiUvS4eK9hPB5DgVzyrKGLDNtzfm8T07h2KT4EIhFconQ@rlIMTewVfUSCAfVi9ICgz2tgYzjoePsJ7Nhg8jg3uyQA6Fpakfmpq7jeO4hAYA==;0400k7BRB106MVuVebKatfMjIAQ5nOFA6L0wjPuLbMR@Tonr1FvaoH6PXvVN9YtfyA8fnMWhGBYHnHo3Cs6nruhdD2TmOqLA3SnRXy5IEEQhQX20bF4Z/Koq4/RkTFPqszWzFgCMf3f9jN8x6I29Qa49RnCqEmpDUxtK1eHfkZ0HoJzSt0oGtrebhg7@chKh9//Uv4JtRr19iXv376@SVFU2NJVPfVKB9sZJGPKwfLmA74FxvqDIKJyE1zZfXIb9XsGtivnYrDsr2qZilUTVoWgt0JaJI4LgNdTJTtU6fOHu/7dfLkgQRCFBfbRsXhn8qirj9GRMU@qzNbMWAIx/d/2M3zHojb1Brj1GcKoSakNTG0rvUSbJ@Ltz3R3@1lLYqHZEuaHhC1bVeu5togQSuFobQol/IYbULIWPc9gLcHAfgAAS@t4Gw7P0Dx00fB4uFb0DfymwoThCemd0nft3EEObkDYJHwX9RVU3i6IkqbVWU45Ddd59r5gHsrnvgeDNsJKiOtN5pxtXd5SW7Pfz9Do7ABZtJ8o2YYNM3ojpRB45JgJ53I96WzuNwWUdUHpyGsju7yZHbT@hap8T0tJPmnbeZTC3o7rRuIcSFdAYUOJPdcEoDvlKqcW/vgHzj486sjgyUO@AInpd@UykzlhvKatVjussydRjZjLjFmQWppRl6Bv4pp48B2PR0LUM6Rn3JtHEfF9hXdZ4DRRiwxmZVjl9I4YEEn4xf96XN89p9kmlqhWg5dRbGWPI8BYO/ZZ80vd9iQcp7l8EoPVZOWa7AmiMkXqUGDPfSuQntRoJIDtkZkKGXPnCVEXBYmLQVG6Wu5dBhCiebBE66IIElXD0hEMtvQl2olrNgUNP1zN8uYOUkuTGInrBs5KtrjntEcVmW1kMV3qhf8WVO3WSnrbJSNyIsUwBHF7gRT1d55FTSZBNQWsUUA3pDXHzRR0J0Z3KrDBtMp15KDg/66MdEfb0TY@Xry83Rel4W1HHFFAN6Q1x80UdCdGdyqwwbTKdeSg4P@ujHRH29E2Pl68vN0XpeFtRxxRQDekNcfNFHQnRncqsMG2g3qKNiBsndmONlKQFMFWIK2/lD4vAGQ/rVCfLkU6F76x386SrtAYwz4xylStqUSBZREAR7XFJDOb8dIdPEb9km9ceHDKRCiLCgbgXPmizjHlWmCRBdNDNsuaxAwAEbReSBBWhu07LmRdt5U4CpewI6LuRi@5UtR8GdOKIAV4U0wHzj486sjgyUO@AInpd@UykzlhvKatVjussydRjZjLjFmQWppRl6Bv4pp48B2PR0LUM6Rn3JtHEfF9hXdZ4DRRiwxmZVjl9I4YEEn4xf96XN89p9kmlqhWg5dRbGWPI8Ch2Va2TNOpLvZ4Jj3jFVvR9xD6KYGuP2YbamY1MUAnGgiFKOsXcSILjFijINS9@MWCtJpkmxKJc/9EzqitIU7unBB@YOwhnBW5W0fT1RYFrtAop9ur3eOywb6ufBQksTe7J92Ykwts7dkKVn7IgucUR0AXiOOsg8v8V8c@YhydhouMC@WQLqLHkbAxyEijNfUC51oXT0@qivf@3sgJA50Of@EOF5rBO9KsI9aPJXbZVBw/ayGoJ1RiBZG99q0OiMyhKBoBIDjU225vlGB@J/1GcmFHKqqK1q/ndzGlibGwvcV6o5HNUpq@MHXDjLI8B1muLvcABo1AMe0kNVrzYC2s6sghsJdXQY1MR07u1VFJ18AsihqSFF0zZ1NtOhMB7TkDBONNq3ar7gVmYQocuXB24ytvipQ7QmCkovzXC5kIvmRVhNjLDRgS0NKb@c/CYjcOfR3QECX0VS@uusapmLU5LyxzFsZ3o87Zr4LsPQNj12LO7g0Y/ubNt/8Pf3pv8USSJvxEKpWRa4wDyJDezaUYM23N@bxPTuItc6FG@LDX2GcjQ2lB8WRs/IBgQ4NdngEd/d5rmbZtdhGxYUz0JrWnkh7W9KfZhyHXF4@GICyvbqlr028/3Pq/hRHmaSBNsYlvftB7Pz68hRV8flS7U9rrgRYfF/t55L09Q78/WKn4cPOYe85lXFCTh7vMc0vUF2UqjaQTp7FThxr88XPLaQ6jw7NEYi1vOPPF7CXlj79gn@Pe7WXNk3DDmh8hLtT6M2WEC7GS67l8K9y26/rWtQIRJ7Rrl00UhlhockDV5LfwFOalPPsQb3W96Q622OGf8CvevFZCSEUAI2ZkykdU1n5Rwvmk5lvVE8rScBf2@cYFXB2GDXcjpghmjAMddvFicdokFWgZWJFZbNNLuuP82AxtH7L/6q7exmpV6W0NS1Dk38tQO5kuWsug1gG2oJSHfyi9ux3c@0/Q4WGCzkn2k9nOCs7IgqHkV6RzceiYoKsdnatIf@o@pBQYJ0R5xgR/xyNPLfBP96EpgIacAqNXEBq3/Um0@T0DRshuSPR0pZdfUfC9kdwK7EcoxqzgUN99A62M@HjELlgBJIoNDRrkPRgZ1CnGg3aE4aFHyYCYRDYr6wZs0wKd/R5g@TzheO2Vfpu4NgB@FMNYDz6hm6EOKvK7jiCFB8Mzk6qnMApmfGlHY8KxbdcW7vOgVv2X5tMmwxr6eunLcplqpt6di@7tzGach4j57RalrPACi8eTB77NDPfgA6hlNo3xg9z6ivecjCp2wpf4aIv3f/6pjDeG@UNM4TPimQ9Y3aguhPbWgzUPZ2UaP8zl8SUTzw/NasUF@QZOGgkwsyu6zdhRRIEH3yyLwAAXgXZDCJjTAWO1@bR0DuOxn3ELnr@vzP4ybFfUHE2UXKlgnOSxfRcqXG5PywVuqTbA6C9kbp@pyNLAqk5y4rZu1Fmxw5uD5qB5lcI6F7eEEAS4VTJpNH/s3@OK2MaiuhAyVKBvy4uSlX2tMl5Pq8psqunlJ2ruIGw2BLi2NpSlHu1z26poc/YjOEOFVV@tcH3ru2WK/ytt8Ok8A1jbMhSKN@6@/MwPgUZas/NRn0NnxKloNxcPaUzPtu@hsUXx8PrL/sgEx7g==";
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
//        String cookies = "b-user-id=85477736-98f0-11a4-4d7f-e21dbea69097; b-user-id=ODU0Nzc3MzYtOThmMC0xMWE0LTRkN2YtZTIxZGJlYTY5MDk3; odd_f_type_36826212=VFE9PQ==; box4pwd_notshow_36826212=MzY4MjYyMTJfTg==; CookieChk=WQ; iorChgSw=WQ==; myGameVer_36826212=XzIxMTIyOA==; box4pwd_notshow_37015545=MzcwMTU1NDVfTg==; myGameVer_37015545=XzIxMTIyOA==; box4pwd_notshow_37587241=Mzc1ODcyNDFfTg==; myGameVer_37587241=XzIxMTIyOA==; ft_myGame_37587241=e30=; lastBetCredit_sw_37587241=WQ==; lastBetCredit_37587241=NTA=; protocolstr=aHR0cHM=; test=aW5pdA; bk_myGame_37587241=e30=; login_37587241=MTc0NzEzODEwMQ; cu=Tg==; cuipv6=Tg==; ipv6=Tg==";
//        headers.add("cookie", cookies);

        // 构造请求体
        String requestBody = String.format("p=chk_login&langx=zh-cn&ver=%s&username=%s&password=%s&app=N&auto=CDDFZD&blackbox==%s",
                Constants.VER,
                params.getStr("username"),
                params.getStr("password"),
                blackbox
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
        // 打印 Set-Cookie
        /*List<String> setCookieHeaders = response.headers().get("Set-Cookie");
        if (CollUtil.isNotEmpty(setCookieHeaders)) {
            log.info("返回的 Set-Cookie: {}", JSONUtil.toJsonStr(setCookieHeaders));
        }*/
        // 检查响应状态
        if (response.getStatus() != 200) {
            JSONObject res = new JSONObject();
            res.putOpt("success", false);
            if (response.getStatus() == 403) {
                res.putOpt("code", 403);
                res.putOpt("msg", "账户登录失败");
                return res;
            }
            res.putOpt("code", response.getStatus());
            res.putOpt("msg", "账户登录操作失败");
            return res;
        }

        // 解析响应
        Document docResult = XmlUtil.readXML(response.body());
        JSONObject responseJson = new JSONObject(response.body());
        if (!"100".equals(responseJson.getJSONObject("serverresponse").getStr("msg"))) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", responseJson.getJSONObject("serverresponse").getStr("code_message"));
            if ("109".equals(responseJson.getJSONObject("serverresponse").getStr("msg"))) {
                responseJson.putOpt("code", 109);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要去盘口重新设置登录账号");
            }
            if ("106".equals(responseJson.getJSONObject("serverresponse").getStr("msg"))) {
                responseJson.putOpt("code", 106);
                responseJson.putOpt("success", false);
                responseJson.putOpt("msg", "需要去盘口重新设置登录账号密码");
            }
            return responseJson;
        }
        Object token = XmlUtil.getByXPath("//serverresponse/uid", docResult, XPathConstants.STRING);
        if (ObjectUtil.isEmpty(token)) {
            responseJson.putOpt("success", false);
            responseJson.putOpt("msg", "账户登录失败");
            return responseJson;
        }
        responseJson.putOpt("success", true);
        responseJson.putOpt("token", token);
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
        String queryParams = String.format("ver=%s",
                Constants.VER
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
