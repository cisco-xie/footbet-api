package com.example.demo;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.IdUtil;
import cn.hutool.http.HtmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.model.vo.LoginVO;
import net.sourceforge.tess4j.Tesseract;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
class FalaliApplicationTests {


    //    @Test
    public String code(String uuid) {
        String url = "https://3575978705.tcrax4d8j.com/code?_" + System.currentTimeMillis();
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("2a29530a2306", "636e8262-73f0-4eed-984f-606b1a029d72");
        HttpResponse resultRes = HttpRequest.get(url)
                .addHeaders(headers)
                .cookie("2a29530a2306=" + uuid)
                .execute();
//        resultRes.bodyStream()
        String fileName = "D://code-" + uuid + ".jpg";
        resultRes.writeBody(fileName);
        String result = null;
        try {
            File image = new File(fileName);
            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath("D://tessdata");
            tesseract.setLanguage("eng");
            result = tesseract.doOCR(image);
            System.out.println(result);
        } catch (Exception e) {
            e.printStackTrace();
        }
        assert result != null;
        return result.trim();
    }

    @Test
    public void login() {
        String uuid = IdUtil.randomUUID();
        String code = code(uuid);
        String url = "https://3575978705.tcrax4d8j.com/login";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("content-type", "application/x-www-form-urlencoded");
//        headers.put("2a29530a2306", uuid);
        String params = "type=1&code=" + code + "&account=cs22222&password=WEwe2323";
        HttpResponse resultRes = HttpRequest.post(url)
                .addHeaders(headers)
                .cookie("2a29530a2306=" + uuid)
                .body(params)
                .execute();
//        System.out.println(resultRes.getCookies());
//        System.out.println(resultRes.getCookieStr());
        System.out.println(resultRes.getCookieValue("token"));
//        String result = HttpRequest.post(url)
//                .addHeaders(headers)
//                .cookie("2a29530a2306="+uuid)
//                .body(params)
//                .execute().body();
//        System.out.println(result);
    }

    @Test
    void contextLoads() {
        String url = "https://3575978705.tcrax4d8j.com/member/accounts";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=188d93ccac3f160b457da2c57037f400f1ed8bdd; defaultLT=PK10JSC; page=lm; ssid1=e4ac3642c6b2ea8a51d3a12fc4994ba7; random=4671; __nxquid=nKDBJcL6SZckLHDCiGlHdK0vbTqqcA==0013; token=188d93ccac3f160b457da2c57037f400f1ed8bdd; JSESSIONID=BD5FF9565DC71375B21F81A96CF946CD");
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
        System.out.println(result);
    }

    @Test
    void bet() {
        String token = "fde05e8368896feafde7a686929d06ad0136d233";
        String url = "https://3575978705.tcrax4d8j.com/member/bet";
        String params = "{\n" +
                "  \"lottery\": \"PK10JSC\",\n" +
                "  \"drawNumber\": \"33388395\",\n" +
                "  \"bets\": [\n" +
                "    {\n" +
                "      \"game\": \"DX2\",\n" +
                "      \"contents\": \"D\",\n" +
                "      \"amount\": 1,\n" +
                "      \"odds\": 1.9939,\n" +
                "      \"title\": \"亚军\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"fastBets\": false,\n" +
                "  \"ignore\": false\n" +
                "}";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; index=; index2=; oid=188d93ccac3f160b457da2c57037f400f1ed8bdd; defaultLT=PK10JSC; page=lm; token=" + token);
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        String result = HttpRequest.post(url)
                .body(params)
                .addHeaders(headers)
                .execute().body();
        System.out.println(result);
    }

    @Test
    void history() {
        // 这个方法获得的result是html格式，获取到对于标签里的内容，转成json格式
        String url = "https://3575978705.tcrax4d8j.com/member/history";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; page=lm; index=; index2=; defaultLT=PK10JSC; oid=41654675f4874022d74052392e3068891ffd0fc9; ssid1=cb35dc1236c416659b7a8f7265006c5c; random=5408; token=41654675f4874022d74052392e3068891ffd0fc9; __nxquid=wtXcF8L6SZeYza0wiGnuFawvszaqcA==0013; JSESSIONID=9DE36FC33B0AA64B23B7A4202DE91FDE");
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
        System.out.println(result);
    }

    @Test
    void historyHtml() {
        String url = "https://3575978705.tcrax4d8j.com/member/history";
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9");
        headers.put("cookie", "defaultSetting=5%2C10%2C20%2C50%2C100%2C200%2C500%2C1000; settingChecked=0; page=lm; index=; index2=; defaultLT=PK10JSC; oid=41654675f4874022d74052392e3068891ffd0fc9; ssid1=cb35dc1236c416659b7a8f7265006c5c; random=5408; token=41654675f4874022d74052392e3068891ffd0fc9; __nxquid=wtXcF8L6SZeYza0wiGnuFawvszaqcA==0013; JSESSIONID=9DE36FC33B0AA64B23B7A4202DE91FDE");
        headers.put("priority", "u=1, i");
        headers.put("x-requested-with", "XMLHttpRequest");
        String result = HttpRequest.get(url)
                .addHeaders(headers)
                .execute().body();
        System.out.println(result);
        // 解析 HTML
        Document doc = Jsoup.parse(result);
        List<Map<String, String>> allTableData = new ArrayList<>();
        List<Map<String, String>> lastWeekData = new ArrayList<>();
        List<Map<String, String>> thisWeekData = new ArrayList<>();

        // 处理两张表格
        Elements tables = doc.select("table.list");
        if (tables.size() >= 2) {
            // 第一张表格：上周数据
            Elements lastWeekRows = tables.get(0).select("tbody tr");
            for (Element row : lastWeekRows) {
                Map<String, String> rowData = new HashMap<>();
                Elements cols = row.select("td");
                if (cols.size() >= 6) {
                    rowData.put("date", cols.get(0).text());
                    rowData.put("count", cols.get(1).text());
                    rowData.put("betAmount", cols.get(2).text());
                    rowData.put("forceAmount", cols.get(3).text());
                    rowData.put("cm", cols.get(4).text());
                    rowData.put("dividend", cols.get(5).text());
                    lastWeekData.add(rowData);
                }
            }

            // 第二张表格：本周数据
            Elements thisWeekRows = tables.get(1).select("tbody tr");
            for (Element row : thisWeekRows) {
                Map<String, String> rowData = new HashMap<>();
                Elements cols = row.select("td");
                if (cols.size() >= 6) {
                    rowData.put("date", cols.get(0).text());
                    rowData.put("count", cols.get(1).text());
                    rowData.put("betAmount", cols.get(2).text());
                    rowData.put("forceAmount", cols.get(3).text());
                    rowData.put("cm", cols.get(4).text());
                    rowData.put("dividend", cols.get(5).text());
                    thisWeekData.add(rowData);
                }
            }
        }

        // 转换为 JSON
//        JSONArray jsonResult = JSONUtil.parseArray(allTableData);

        JSONObject resultJson = new JSONObject();
        resultJson.put("lastWeek", lastWeekData);
        resultJson.put("thisWeek", thisWeekData);

        // 输出结果
        System.out.println(resultJson);
    }

}
