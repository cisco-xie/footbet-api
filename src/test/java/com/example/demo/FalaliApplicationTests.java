package com.example.demo;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.http.HtmlUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.api.FalaliApi;
import com.example.demo.api.LoginService;
import com.example.demo.model.vo.LoginVO;
import jakarta.annotation.Resource;
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

    @Resource
    private LoginService loginService;

    @Resource
    private FalaliApi api;

    @Test
    public void base64() {
        String encoded_str = "oZy8OOERVAj1UHaWypnVHchCivPwKYQxow6CQZAsWrN4yq8hJO4hQ1qIbEroJZQS6nD8KIDfyyHMjAVFryc=";
        System.out.println(Base64.decodeStr(encoded_str));
    }

    @Test
    public void password() {
        for (int i = 0; i < 10; i++) {
            System.out.println(RandomUtil.randomString(6) + RandomUtil.randomNumbers(2));
        }
        System.out.println(RandomUtil.randomStringUpper(8));
    }
    @Test
    public void loginWeb() {
//        String response = loginService.simulateLogin("csa1", "WEwe1212", "6523").block();

        try {
            String uuid = "1d17d23f-50c1-45a1-a199-c1acf32865c8";
            String fileName = "d://code-" + uuid + ".jpg";

            Map<String, String> headers = new HashMap<>();
            headers.put("priority", "u=0, i");
            headers.put("sec-fetch-user", "?1");
            headers.put("upgrade-insecure-requests", "1");
            headers.put("Cookie", "visid_incap_3042898=M3qcw+ILSISvGap1LvJiwA+oTmcAAAAAQUIPAAAAAADH+pwU6Aak+93oy43G+3Ln; nlbi_3042898=hTWZBeCWBFRoL63okFTcAQAAAAC1RatlySm0DPITeb1GQvWh; incap_ses_1509_3042898=K6zFVHlWimXJpZ/smgvxFA+oTmcAAAAA9M5cOgcy+ZwplxPmtQRjnQ==; ssid1=3e91457b967da350118bd75e026ee660; random=9241; b-user-id=5f7b230f-c05f-86ba-9111-7527ee9abbe9; 2a29530a2306="+uuid);

//            HttpResponse requestCode = HttpRequest.post("https://7410893256-am.tcr195uhyru.com/code?_=1733210461638")
//                    .addHeaders(headers)
//                    .execute();
//            requestCode.writeBody(fileName);
//            Tesseract tesseract = new Tesseract();
//            tesseract.setDatapath("D://tessdata");
//                tesseract.setDatapath("/usr/local/resources/projects/falali/tessdata");
//            tesseract.setLanguage("eng");
//            File image = new File(fileName);
//            String code = tesseract.doOCR(image).trim();

            // 构建登录 URL
            String loginUrl = "https://7410893256-am.tcr195uhyru.com/login";
            String code = api.code(uuid, null);
            String param = "type=1&account=csa1&password=WEwe1212&code=" + code;

            // 构建并发送请求
            HttpResponse response = HttpRequest.post(loginUrl)
                    .header("priority", "u=0, i")
                    .header("sec-fetch-user", "?1")
                    .header("upgrade-insecure-requests", "1")
                    .header("content-type", "application/x-www-form-urlencoded")
                    .header("Cookie", "visid_incap_3042898=M3qcw+ILSISvGap1LvJiwA+oTmcAAAAAQUIPAAAAAADH+pwU6Aak+93oy43G+3Ln; nlbi_3042898=hTWZBeCWBFRoL63okFTcAQAAAAC1RatlySm0DPITeb1GQvWh; incap_ses_1509_3042898=K6zFVHlWimXJpZ/smgvxFA+oTmcAAAAA9M5cOgcy+ZwplxPmtQRjnQ; ssid1=3e91457b967da350118bd75e026ee660; random=9241; JSESSIONID=43B429EB2458357C2572599E1061C66F; b-user-id=5f7b230f-c05f-86ba-9111-7527ee9abbe9; 2a29530a2306=" + uuid)
                    .body(param) // 设置表单内容
                    .timeout(0) // 设置超时时间
                    .execute();

            // 打印响应
            System.out.println(response.getCookies());
        } catch (Exception e) {

        }
    }

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
