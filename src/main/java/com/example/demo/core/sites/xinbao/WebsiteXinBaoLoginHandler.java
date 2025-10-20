package com.example.demo.core.sites.xinbao;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.XmlUtil;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ApiUrlService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.constants.Constants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.factory.ApiHandler;
import com.example.demo.model.vo.ConfigAccountVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 智博网站 - 登录 API具体实现
 */
@Slf4j
@Component
public class WebsiteXinBaoLoginHandler implements ApiHandler {

    private final OkHttpProxyDispatcher dispatcher;
    private final WebsiteService websiteService;
    private final ApiUrlService apiUrlService;

    @Autowired
    public WebsiteXinBaoLoginHandler(OkHttpProxyDispatcher dispatcher, WebsiteService websiteService, ApiUrlService apiUrlService) {
        this.dispatcher = dispatcher;
        this.websiteService = websiteService;
        this.apiUrlService = apiUrlService;
    }

    private final String blackbox = "0400YbjucgPeDpqVebKatfMjIO8YBzlCJq/2DDf4JvnHM4u3faNBvjgK6YxjLgZYa9QCB9jmGPYPT7ApgjP8690QpWTmOqLA3SnRszrsyHb4eq5RX6RUwOuiZIKQ7S76F/3NKKuEZlC6aNeFXjQlk5yc/y5AnYURFezU8VWo4o1/6JRuPNmDZ0pvsV@LIlIpEMJ817BVzdqtCzgDNQXe7Za4u6ih@vuXNadMulQblCIKk9ETqW1ml8tAcf6a@9aA@etaKh6MKylEKJyH4g0VvkdOXC9JuiiELXOzTn3ScfrBfYSzOuzIdvh6rlFfpFTA66JkgpDtLvoX/c0oq4RmULpo14VeNCWTnJz/LkCdhREV7NTvUSbJ@Ltz3R3@1lLYqHZEHwYz4NPJrGI1/Ap35H6qsNiLOt/ZJQDNHTR8Hi4VvQN/KbChOEJ6Z3Sd@3cQQ5uQNgkfBf1FVTeLoiSptVZTjkN13n2vmAeyue@B4M2wkqI603mnG1d3lITh1KH71MCiVFoAhenj8D5OSKeu3ER99O1sRoFJwLN@kuN7pHlNT@U9GC17jlnhcysWvqTKCrrf6Yf0/pTJUUwNu4MVy/aYvwydKgnOIDKXq4HnEqNOos1c6njJgQh/4vXJiqy0MXMQOThNipDmXv9I185O@yC2f3lLEO0Tay66NZEyiLNePemJKSIdwO9O5ZtntuUkG6NTcat@EJ0Jp/4GLDM4fZkS@HhVKFtj4RUEzX0hM3tAJxtauODchk2WrH2LOmVQKLUd6c6@kxFUVlrs91VtuM/Pmah1fJQrFSBW1n74tpcOq4gNfyJw5Y9lzLhejolH6jy42HYF5oyZHJe3UxF1sQ4XaFI6uvOkAijc@snP9@SWhwxpHguUhwhyzyEioquMr6B3leFFg3SnwvjkTEmWwQPJZ5nCGPGKh21isC2ODMcwYCQtMuQQ3gvsR8jwjs5Ca2/wBdhim9vK/UsQ5XOYm0OqKbAtjgzHMGAkLTLkEN4L7EfI8I7OQmtv8AXYYpvbyv1LEOVzmJtDqimwLY4MxzBgJKaSAWnu1Ij06yFodg/w6rrQcsfKD46f8XRPnNoqVGPkmn0ytLNkfKe5S4UTur7FdL9MOnRTMthL4qMDk5M2ilFC2dweZ48L7VLB6M5fl2EoZiuy7XetzhHVhghPLNHC/w9dek5r8WH9djzltoetMiQsjWig6UIW4gQy/m2yvWeDc5oAtDUKyzbEihBEfd6hmPi8fptuT4V@57LnmsP38HaJ@KmAMZ21YUzZey/rZNEccDD874q5/LdLrTSVqlkG8Be9OZf3fNV/dVjzwug4nqXYOWqFOzJ855xSY1DdVpEfcTtkQ9e89/9e/728hGV9pfLEMokGoGJxIpJQceGZLcRFhNYE6nRYvMQPcd6kSm4zy0Ef4E9TJOSW/40pM8u5mQgm2/HTB0IMhZ5ib08SA@6w1OP809/BF8S4y8wXSQSl@peHjiiVN2uSLJoZYGfqlamhJTVCr5rSVXvGSUaueuL/Coj8cde/eLXIdSEIWQaYhtHN/KXIY9/AFjix/fbnRQAkjr8dCw2n26vsWZYVT5BAvp7DNvkGIUxBqvDH@1/WkqtIKIc@czEBgFsPd/ul/qpjQapqsVKN1pARUU7D18U@nB3MDYdlP@OZFmetD08o3sQgCz8kuQxQt7XP4r5YuUcxh0CkYdlXsOM3kJR5cNfpSeAjoyDntRE89JsBjY19/ucfGsuFBQv/Jp9uRMeisaVEWiXbOH2BPegm2xauZ6M3V7mp9Dm2Jg3pJong7D2@U3nDRIooatykrZV6LhXIsDOJ3n5q2sMj/fgWq4beK3y9zS@c@uIkPzYYEdHnfqO6DrODVo51K8nt6/v8QB71GWFkzwq7i9x9HtCrkoANuGWN5sYH5J7qC6FSgx6l/txtroy/vRBBCujeUX0qRHpSe3JOeex5UoBtYPc@or3nIwqdsKX@GiL93/@qYw3hvlDTOEz4pkPWN2qrnVUoisMmzZ7tKGy2vXG1jX4i2fu2hpG3W7uZ9Bf3i51CF1O0vgfAUdTSSi/87EXLYJvcNhGxIH2VYNf0ZbJCQ7ByoNV3AoKORhO05KSM1/1D5RTRzjVpX02SO59vGGYu565yeP2eNY/5HkWrfPCQEy6Q/lc7Cad9QwL/LV6PkeSq@j7nQPn3kM3bk3CQRw3d7nObrklBnvGqb8a0hqjwUVSP91m74MWuoyiPWk8nZEwJrJ/g@C32Ob93Znmj0FIWwC13peuaqzYcDEFl6bUM1n74tpcOq4j8jrQj5IlJcQcqjFdGI72XhmyQO2fkSNJJciu8p24aip3aCOyASM26WcE3wYKJFJ9@piVeC6E6HOOpPGco9mW8L6ClmhYmFZEGeNnIZu5rmyUg06WqekZYhAOOFUnadqixOvk/lnDp3Tn/3HY2W3gG26RJ1YwCFKZTecNEiihq3KStlXouFciw0oR671r6APKaeTQzU2Cex/kNNTM75QsadiZube5ZvDwp0rhFdoKvsWzybegor3IDqwzM5m9v5czg7G0j6xuXosAf5oy6YYfcjfcgZ8uoMdcbkj0dKWXX1IoB/325jbHJACGGN1ri3LJt2ywAbUvReYD9SQixzL@1Oa0zmfmv6anWfaF6bWlD20CQcQ8FNLgTfLioaKPoigGa7WNIbTo6XXpHJOG6w4lw0LSmhe7Z8uR@jVAggkRRnfy/d/HL@rzNHsv@rj3fklf0zSDuCeJiwFXGlp9c9R8dzAU7txiq9KV0UoDrU6P0sNtdO62Cf7wJZUcQ4JRuWcZgS9@RYVhSkcgHe71z8R7KemT6nbnVohMeQ6kRFYbYjX2XlrQjJCqt56pzBJbLcUs=;0400k7BRB106MVvjK9GFecOQizUnK3ylqdlX3ZwAwFt/1qt3wHCEpJfCVtqM9PD6A8Ex9L39W0kQRDS@ZehtGuQEZI1KtGYeaD1eKMkxAFLLGZVda0@Ak3fmiDDYyQBo/VHZc5FkTBX8w34spfy1EHsK4tvxIinogikj8VWo4o1/6JTdBuJUUgiyRyfc2@Ku1oVAJS5h4K3htZnZc2yfH3dPQXiFiuSPbc@@W1qzFTE6a1@lZlNbRcjLxf6a@9aA@etaKh6MKylEKJyH4g0VvkdOXKI5tcmPADHJTn3ScfrBfYTc3yE5sEBUaAkLY9DQV49/Tv8aX6GnY2x9@bX0rkZYzxueLSfUs@wFoieCqxPRxdDvUSbJ@Ltz3R3@1lLYqHZEHwYz4NPJrGI1/Ap35H6qsIl/IYbULIWPxnpYTy4aHo/Yizrf2SUAzR00fB4uFb0DfymwoThCemd0nft3EEObkDYJHwX9RVU3i6IkqbVWU45Ddd59r5gHsrnvgeDNsJKiOtN5pxtXd5SE4dSh@9TAolRaAIXp4/A@TkinrtxEffTtbEaBScCzfmqGiaMMuZbHPRgte45Z4XMrFr6kygq63@mH9P6UyVFMDbuDFcv2mL8MnSoJziAyl6uB5xKjTqLNXOp4yYEIf@L1yYqstDFzEDk4TYqQ5l7/SNfOTvsgtn95SxDtE2suujWRMoizXj3piSkiHcDvTuWbZ7blJBujU3GrfhCdCaf@BiwzOH2ZEvh4VShbY@EVBM19ITN7QCcbWrjg3IZNlqx9izplUCi1HenOvpMRVFZa7PdVbbjPz5modXyUKxUgVtZ@@LaXDquIDX8icOWPZcy4Xo6JR@o8uNh2BeaMmRyXt1MRdbEOF2hSOrrzpAIo3PrJz/fklocMaR4LlIcIcs8hIqKrjK@gd5XhRYN0p8L45ExJlsEDyWeZwhjxiodtYrAtjgzHMGAkLTLkEN4L7EfI8I7OQmtv8AXYYpvbyv1LEOVzmJtDqimwLY4MxzBgJC0y5BDeC@xHyPCOzkJrb/AF2GKb28r9SxDlc5ibQ6opsC2ODMcwYCSmkgFp7tSI9OshaHYP8Oq60HLHyg@On/F0T5zaKlRj5Jp9MrSzZHynuUuFE7q@xXS/TDp0UzLYS@KjA5OTNopRQtncHmePC@1SwejOX5dhKGYrsu13rc4R1YYITyzRwv8PXXpOa/Fh/XY85baHrTIkLI1ooOlCFuIEMv5tsr1ng3OaALQ1Css2xIoQRH3eoZj4vH6bbk@Ffuey55rD9/B2ifipgDGdtWFM2Xsv62TRHHAw/O@Kufy3S600lapZBvAXvTmX93zVf3VY88LoOJ6l2DlqhTsyfOecUmNQ3VaRH3E7ZEPXvPf/Xv@9vIRlfaVLUNHa/XnhE5oVugXwRLvfyrHbxcdMF@Gdf@5Gll1G2CUBCqr2e/gj5m@HqOz7VQNgWGiGLW10nIA7lK/ItCA/58s@SSf/yTq2zPZQ9LQV2GraTZyP3QiBxCYfJUoJ9InmphqlCQ2N4O3oUjw2y0CC8RRzZJ4dJh7Go1LMWPIRjgNInfCZM@xY65uQoIi8qHlhxwOTh50ZSbiMAbe3LsTICd7HTz409tXrIxLQI94M0BmTR1gMP2211XoVMpV3QNT72m6y2UOSuinQ0ZPwKgCbOUgOkEOQczkdZbiv2jm@uHchVh@tDt3oOPBEgSHA2b6vk5M7xnu7we@fM7ucrYhoMas4FDffQOtjPh4xC5YASY5m5I8@DtZYLj4KXW1q0GO8yp/WapbAbIRsWFM9Ca1p5Ie1vSn2Ych/XyLU8112Un2XlrQjJCqtak955pGAV2tFXx@VLtT2uuBFh8X@3nkvovoZiuEddgY85h7zmVcUJBNBM7WaTBsiSqNpBOnsVOECbYYZaNA55/eavc9zswpRkGo4SrD01sLZhPFfX62UDU2EzyxQZxil2nqVAIRVjHhIlg5dYU/4HuhYf5juFXmEhN8rIlk0BMLT0pjcJe4u0Hx3lOYwOC6W5lOOcFbElIS/146@zK9MFVuXfxARHZtY55sIgPMzEM2EaZhZP2yMi94rv6vcNEBUxAhW@WgLyT7M4UD/3eziAM4S8btCkcfEr8oIclVMfCa6z4CjVrALSmKWriKxnaKSHT1xsAWYohQ4EPUHGodbPun1bAiSG3LV@hcNaKk7u8FxPd/ygPljoorOvWCMIlb4NSu3qjPKSaR6pjBe25YsvaGnVicGkOCMLdRLj@ztmHutWuJqm@6NgpEC2TX11@1R8FfDK7KI4VexjYy7fH7b8IcDgwFvHMbrx036sCHEdXq8YxnodHGU4Qe0QC7RwG4FtiYN3oUl0hgfiAKf0x0jbxrqEFRF5S5oYfoEEmyYuJV1snS92KANH@P2CPm@9S/w@VA@R3VSZZlZyUIGVUx6JCPUwET7iI9uf8lojGK2KWeoLeYE5clVm@bZv8MKyq3QGuQT/oaqVxXfBJ2g8KAIj029wzKZEGu2XEOjNMGGO8en/WYQpa7xAIJ1qrpfKdQ9jnBhhfdkDrUfoJUvvjDtmn1fMWAHJevBddlWqgx8zUFRjz/Lh92TgEOQCOuKAzI34PrjwIMHBQq8OK4dIao805QPSPaPkGq60wilrkzItnYEuMyD8eCUjSVUH7L1W7V4Qvz4IDrx/g55YdIaKJtYvtC0poXu2fLkfo1QIIJEUZ38v3fxy/q8zc@lPkhXcptOXkR7QQemt/1kFD1JeJjDnkWbtSxTjSjNn6FRYwmUplw=";
    private final String userAgent = "TW96aWxsYS81LjAgKFdpbmRvd3MgTlQgMTAuMDsgV2luNjQ7IHg2NCkgQXBwbGVXZWJLaXQvNTM3LjM2IChLSFRNTCwgbGlrZSBHZWNrbykgQ2hyb21lLzEzOC4wLjAuMCBTYWZhcmkvNTM3LjM2";

    /**
     * 构建请求头
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public Map<String, String> buildHeaders(JSONObject params) {
        // 构造请求头
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/x-www-form-urlencoded");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,pt-BR;q=0.8,pt;q=0.7");
        headers.put("Connection", "keep-alive");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.put("sec-ch-ua", Constants.SEC_CH_UA);
        headers.put("User-Agent", Constants.USER_AGENT);
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");

        return headers;
    }

    /**
     * 构建请求体
     * @param params 请求参数
     * @return HttpEntity 请求体
     */
    @Override
    public String buildRequest(JSONObject params) {
        // 构造请求体
        return String.format("p=chk_login&langx=zh-cn&ver=%s&username=%s&password=%s&app=N&auto=CDDFZD&blackbox=%s&userAgent=%s",
                Constants.VER,
                params.getStr("username"),
                params.getStr("password"),
                blackbox,
                userAgent
        );
    }

    /**
     * 解析响应体
     * @param response 响应内容
     * @return 解析后的数据
     */
    @Override
    public JSONObject parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult response) {
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
        // Document docResult = XmlUtil.readXML(new StringReader(response.getBody()));
        JSONObject responseJson = new JSONObject(response.getBody());
//        if (responseJson.getJSONObject("serverresponse").getInt("ltype") == 3) {
//            JSONObject res = new JSONObject();
//            res.putOpt("success", false);
//            res.putOpt("msg", "账户被限制，只能查看交易状况以及帐户历史");
//            return res;
//        }
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
        // Object token = XmlUtil.getByXPath("//serverresponse/uid", docResult, XPathConstants.STRING);
        String token = responseJson.getJSONObject("serverresponse").getStr("uid");
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
        Map<String, String> requestHeaders = buildHeaders(params);
        String requestBody = buildRequest(params);

        // 构造请求体
        String queryParams = String.format("ver=%s",
                Constants.VER
        );

        // 拼接完整的 URL
        String fullUrl = String.format("%s%s?%s", baseUrl, apiUrl, queryParams);

        // 发送请求
        OkHttpProxyDispatcher.HttpResult resultHttp;
        try {
            resultHttp = dispatcher.execute("POST", fullUrl, requestBody, requestHeaders, userConfig, false);
        } catch (Exception e) {
            log.error("请求异常，用户:{}, 账号:{}, 参数:{}, 错误:{}", username, userConfig.getAccount(), requestBody, e.getMessage(), e);
            throw new BusinessException(SystemError.SYS_400);
        }
        // 解析响应并返回
        return parseResponse(params, resultHttp);
    }
}
