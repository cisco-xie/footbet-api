package com.example.demo;

import cn.hutool.core.util.XmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import com.example.demo.api.HandicapApi;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.w3c.dom.Document;

import javax.xml.xpath.XPathConstants;
import java.util.*;

@SpringBootTest
class FootballApiApplicationTests {

    @Resource
    private HandicapApi api;

    @Test
    void testa() {
        String url = "https://www.isn88.com/membersite-api/api/member/authenticate";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/json");
        headers.put("locale", "zh_CN");
        JSONObject params = new JSONObject();
        params.putOpt("username", "XXHKC6610");
        params.putOpt("password", "AAss3344");
        HttpResponse resultRes = HttpRequest.post(url)
                .addHeaders(headers)
                .body(params.toString())
                .execute();
        System.out.println(resultRes.body());
    }

    @Test
    void ps3838() {
        String url = "https://www.ps3838.com/member-service/v2/authenticate?locale=zh_CN&_="+System.currentTimeMillis()+"&withCredentials=true";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/x-www-form-urlencoded");
        String params = "loginId=H610000001&password=AAss3344&captcha=&captchaToken=";
        HttpResponse resultRes = HttpRequest.post(url)
                .addHeaders(headers)
                .body(params)
                .execute();
        System.out.println(resultRes.body());
    }

    @Test
    void mos077() {
        String url = "https://m061.mos077.com/transform.php?ver=2024-12-24-197_65";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("content-type", "application/x-www-form-urlencoded");
        String params = "p=chk_login&langx=zh-cn&ver=2024-12-24-197_65&username=Cniuxt1122&password=AAss3344&app=N&auto=CDDFZD&blackbox=";
        HttpResponse resultRes = HttpRequest.post(url)
                .addHeaders(headers)
                .body(params)
                .execute();
        Document docResult = XmlUtil.readXML(resultRes.body());
        System.out.println(XmlUtil.getByXPath("//serverresponse/uid", docResult, XPathConstants.STRING));
    }

}
