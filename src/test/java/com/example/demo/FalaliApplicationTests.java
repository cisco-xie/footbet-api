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
import com.example.demo.core.model.UserConfig;
import com.example.demo.model.vo.LoginVO;
import jakarta.annotation.Resource;
import net.sourceforge.tess4j.Tesseract;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.*;

@SpringBootTest
class FalaliApplicationTests {

    @Resource
    private LoginService loginService;

    @Resource
    private FalaliApi api;

    @Test
    public void json() {
        String odds = "{\n" +
                "    \"B1_10\": 9.969,\n" +
                "    \"B1_9\": 9.969,\n" +
                "    \"B1_8\": 9.969,\n" +
                "    \"B1_7\": 9.969,\n" +
                "    \"B1_6\": 9.969,\n" +
                "    \"B1_5\": 9.969,\n" +
                "    \"B1_4\": 9.969,\n" +
                "    \"B1_3\": 9.969,\n" +
                "    \"B1_2\": 9.969,\n" +
                "    \"B1_1\": 9.969,\n" +
                "    \"B10_10\": 9.969,\n" +
                "    \"B10_9\": 9.969,\n" +
                "    \"B10_8\": 9.969,\n" +
                "    \"B10_7\": 9.969,\n" +
                "    \"B10_6\": 9.969,\n" +
                "    \"B10_5\": 9.969,\n" +
                "    \"B10_4\": 9.969,\n" +
                "    \"B10_3\": 9.969,\n" +
                "    \"B10_2\": 9.969,\n" +
                "    \"B10_1\": 9.969,\n" +
                "    \"B2_10\": 9.969,\n" +
                "    \"B2_9\": 9.969,\n" +
                "    \"B2_8\": 9.969,\n" +
                "    \"B2_7\": 9.969,\n" +
                "    \"B2_6\": 9.969,\n" +
                "    \"B2_5\": 9.969,\n" +
                "    \"B2_4\": 9.969,\n" +
                "    \"B2_3\": 9.969,\n" +
                "    \"B2_2\": 9.969,\n" +
                "    \"B2_1\": 9.969,\n" +
                "    \"B3_10\": 9.969,\n" +
                "    \"B3_9\": 9.969,\n" +
                "    \"B3_8\": 9.969,\n" +
                "    \"B3_7\": 9.969,\n" +
                "    \"B3_6\": 9.969,\n" +
                "    \"B3_5\": 9.969,\n" +
                "    \"B3_4\": 9.969,\n" +
                "    \"B3_3\": 9.969,\n" +
                "    \"B3_2\": 9.969,\n" +
                "    \"B3_1\": 9.969,\n" +
                "    \"B4_10\": 9.969,\n" +
                "    \"B4_9\": 9.969,\n" +
                "    \"B4_8\": 9.969,\n" +
                "    \"B4_7\": 9.969,\n" +
                "    \"B4_6\": 9.969,\n" +
                "    \"B4_5\": 9.969,\n" +
                "    \"B4_4\": 9.969,\n" +
                "    \"B4_3\": 9.969,\n" +
                "    \"B4_2\": 9.969,\n" +
                "    \"B4_1\": 9.969,\n" +
                "    \"B5_10\": 9.969,\n" +
                "    \"B5_9\": 9.969,\n" +
                "    \"B5_8\": 9.969,\n" +
                "    \"B5_7\": 9.969,\n" +
                "    \"B5_6\": 9.969,\n" +
                "    \"B5_5\": 9.969,\n" +
                "    \"B5_4\": 9.969,\n" +
                "    \"B5_3\": 9.969,\n" +
                "    \"B5_2\": 9.969,\n" +
                "    \"B5_1\": 9.969,\n" +
                "    \"B6_10\": 9.969,\n" +
                "    \"B6_9\": 9.969,\n" +
                "    \"B6_8\": 9.969,\n" +
                "    \"B6_7\": 9.969,\n" +
                "    \"B6_6\": 9.969,\n" +
                "    \"B6_5\": 9.969,\n" +
                "    \"B6_4\": 9.969,\n" +
                "    \"B6_3\": 9.969,\n" +
                "    \"B6_2\": 9.969,\n" +
                "    \"B6_1\": 9.969,\n" +
                "    \"B7_10\": 9.969,\n" +
                "    \"B7_9\": 9.969,\n" +
                "    \"B7_8\": 9.969,\n" +
                "    \"B7_7\": 9.969,\n" +
                "    \"B7_6\": 9.969,\n" +
                "    \"B7_5\": 9.969,\n" +
                "    \"B7_4\": 9.969,\n" +
                "    \"B7_3\": 9.969,\n" +
                "    \"B7_2\": 9.969,\n" +
                "    \"B7_1\": 9.969,\n" +
                "    \"B8_10\": 9.969,\n" +
                "    \"B8_9\": 9.969,\n" +
                "    \"B8_8\": 9.969,\n" +
                "    \"B8_7\": 9.969,\n" +
                "    \"B8_6\": 9.969,\n" +
                "    \"B8_5\": 9.969,\n" +
                "    \"B8_4\": 9.969,\n" +
                "    \"B8_3\": 9.969,\n" +
                "    \"B8_2\": 9.969,\n" +
                "    \"B8_1\": 9.969,\n" +
                "    \"B9_10\": 9.969,\n" +
                "    \"B9_9\": 9.969,\n" +
                "    \"B9_8\": 9.969,\n" +
                "    \"B9_7\": 9.969,\n" +
                "    \"B9_6\": 9.969,\n" +
                "    \"B9_5\": 9.969,\n" +
                "    \"B9_4\": 9.969,\n" +
                "    \"B9_3\": 9.969,\n" +
                "    \"B9_2\": 9.969,\n" +
                "    \"B9_1\": 9.969,\n" +
                "    \"GDS_D\": 1.79,\n" +
                "    \"GDS_S\": 2.1825,\n" +
                "    \"GDX_D\": 2.1825,\n" +
                "    \"GDX_X\": 1.79,\n" +
                "    \"GYH_19\": 42,\n" +
                "    \"GYH_18\": 42,\n" +
                "    \"GYH_4\": 42,\n" +
                "    \"GYH_3\": 42,\n" +
                "    \"GYH_17\": 21,\n" +
                "    \"GYH_16\": 21,\n" +
                "    \"GYH_6\": 21,\n" +
                "    \"GYH_5\": 21,\n" +
                "    \"GYH_15\": 14,\n" +
                "    \"GYH_14\": 14,\n" +
                "    \"GYH_8\": 14,\n" +
                "    \"GYH_7\": 14,\n" +
                "    \"GYH_13\": 11,\n" +
                "    \"GYH_12\": 11,\n" +
                "    \"GYH_10\": 11,\n" +
                "    \"GYH_9\": 11,\n" +
                "    \"GYH_11\": 8.8,\n" +
                "    \"DS1_S\": 1.9939,\n" +
                "    \"DS1_D\": 1.9939,\n" +
                "    \"DS10_S\": 1.9939,\n" +
                "    \"DS10_D\": 1.9939,\n" +
                "    \"DS2_S\": 1.9939,\n" +
                "    \"DS2_D\": 1.9939,\n" +
                "    \"DS3_S\": 1.9939,\n" +
                "    \"DS3_D\": 1.9939,\n" +
                "    \"DS4_S\": 1.9939,\n" +
                "    \"DS4_D\": 1.9939,\n" +
                "    \"DS5_S\": 1.9939,\n" +
                "    \"DS5_D\": 1.9939,\n" +
                "    \"DS6_S\": 1.9939,\n" +
                "    \"DS6_D\": 1.9939,\n" +
                "    \"DS7_S\": 1.9939,\n" +
                "    \"DS7_D\": 1.9939,\n" +
                "    \"DS8_S\": 1.9939,\n" +
                "    \"DS8_D\": 1.9939,\n" +
                "    \"DS9_S\": 1.9939,\n" +
                "    \"DS9_D\": 1.9939,\n" +
                "    \"DX1_X\": 1.9939,\n" +
                "    \"DX1_D\": 1.9939,\n" +
                "    \"DX10_X\": 1.9939,\n" +
                "    \"DX10_D\": 1.9939,\n" +
                "    \"DX2_X\": 1.9939,\n" +
                "    \"DX2_D\": 1.9939,\n" +
                "    \"DX3_X\": 1.9939,\n" +
                "    \"DX3_D\": 1.9939,\n" +
                "    \"DX4_X\": 1.9939,\n" +
                "    \"DX4_D\": 1.9939,\n" +
                "    \"DX5_X\": 1.9939,\n" +
                "    \"DX5_D\": 1.9939,\n" +
                "    \"DX6_X\": 1.9939,\n" +
                "    \"DX6_D\": 1.9939,\n" +
                "    \"DX7_X\": 1.9939,\n" +
                "    \"DX7_D\": 1.9939,\n" +
                "    \"DX8_X\": 1.9939,\n" +
                "    \"DX8_D\": 1.9939,\n" +
                "    \"DX9_X\": 1.9939,\n" +
                "    \"DX9_D\": 1.9939,\n" +
                "    \"LH1_H\": 1.9939,\n" +
                "    \"LH1_L\": 1.9939,\n" +
                "    \"LH2_H\": 1.9939,\n" +
                "    \"LH2_L\": 1.9939,\n" +
                "    \"LH3_H\": 1.9939,\n" +
                "    \"LH3_L\": 1.9939,\n" +
                "    \"LH4_H\": 1.9939,\n" +
                "    \"LH4_L\": 1.9939,\n" +
                "    \"LH5_H\": 1.9939,\n" +
                "    \"LH5_L\": 1.9939,\n" +
                "    \"lottery\": \"PK10JSC\"\n" +
                "}";
        odds = StringUtils.isEmpty(odds) ? null : odds;
        JSONObject oddsJson = JSONUtil.parseObj(odds);
        JSONObject twoSidedDXJson = new JSONObject();
        JSONObject twoSidedDSJson = new JSONObject();
        JSONObject twoSidedLHJson = new JSONObject();
        List<String> twoSidedDxPositions = new ArrayList<>();
        List<String> twoSidedDsPositions = new ArrayList<>();
        List<String> twoSidedLhPositions = new ArrayList<>();
        List<UserConfig> positiveAccounts = new ArrayList<>();
        positiveAccounts.add(new UserConfig("account1", 1));
        positiveAccounts.add(new UserConfig("account2", 1));
        positiveAccounts.add(new UserConfig("account3", 1));
        List<UserConfig> reverseAccounts = new ArrayList<>();
        reverseAccounts.add(new UserConfig("account4", 2));
        reverseAccounts.add(new UserConfig("account5", 2));
        reverseAccounts.add(new UserConfig("account6", 2));
        reverseAccounts.add(new UserConfig("account7", 2));
        oddsJson.forEach((k, v) -> {
            // 筛选以 DX（大小）、DS（单双） 和 LH（龙虎） 开头的键
            if (k.startsWith("DX")) {
                twoSidedDXJson.set(k, v);
                twoSidedDxPositions.add(k);
            } else if (k.startsWith("DS")) {
                twoSidedDSJson.set(k, v);
                twoSidedDsPositions.add(k);
            } else if (k.startsWith("LH")) {
                twoSidedLHJson.set(k, v);
                twoSidedLhPositions.add(k);
            }
        });
//        System.out.println(twoSidedDXJson);
//        System.out.println(twoSidedDSJson);
//        System.out.println(twoSidedLHJson);

        assignPositions(positiveAccounts, reverseAccounts, twoSidedDxPositions, twoSidedDsPositions, twoSidedLhPositions);
    }

    public void assignPositions(List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts,
                                List<String> twoSidedDxPositions, List<String> twoSidedDsPositions,
                                List<String> twoSidedLhPositions) {
        Random random = new Random();
        Map<String, Map<String, List<String>>> selections = new HashMap<>();
        // 存储正投和反投结果
        Map<String, List<String>> positiveSelections = new HashMap<>();
        Map<String, List<String>> reverseSelections = new HashMap<>();

        // 记录已分配过的位置，避免重复分配
        Set<String> allocatedPositions = new HashSet<>();

        // 处理所有投项
        processPositions(twoSidedDxPositions, positiveAccounts, reverseAccounts, "_D", "_X", positiveSelections, reverseSelections, allocatedPositions, random);
        processPositions(twoSidedDsPositions, positiveAccounts, reverseAccounts, "_D", "_S", positiveSelections, reverseSelections, allocatedPositions, random);
        processPositions(twoSidedLhPositions, positiveAccounts, reverseAccounts, "_L", "_H", positiveSelections, reverseSelections, allocatedPositions, random);

        selections.put("positive", positiveSelections);
        selections.put("reverse", reverseSelections);
        JSONObject json = new JSONObject(selections);
        // 输出正投和反投选择
        System.out.println("正投选择: " + positiveSelections);
        System.out.println("反投选择: " + reverseSelections);
        System.out.println("json: " + json);
    }

    private void processPositions(List<String> positions, List<UserConfig> positiveAccounts, List<UserConfig> reverseAccounts,
                                  String suffixPositiveKey, String suffixReverseKey,
                                  Map<String, List<String>> positiveSelections, Map<String, List<String>> reverseSelections,
                                  Set<String> allocatedPositions, Random random) {

        // 按照基础位置（不含后缀）对投项进行分组
        Map<String, List<String>> groupedPositions = new HashMap<>();
        for (String pos : positions) {
            String basePosition = pos.substring(0, pos.lastIndexOf('_')); // 提取基础位置
            groupedPositions.computeIfAbsent(basePosition, k -> new ArrayList<>()).add(pos);
        }

        // 对每个基础位置的投项进行处理
        for (Map.Entry<String, List<String>> entry : groupedPositions.entrySet()) {
            String basePosition = entry.getKey();
            List<String> positionList = entry.getValue();

            // 确保每个位置只分配一次
            if (allocatedPositions.contains(basePosition)) {
                continue; // 如果该位置已分配，跳过
            }

            // 随机决定正投和反投的后缀
            boolean nextBoolean = random.nextBoolean();
            String selectedPositiveKey = nextBoolean ? suffixPositiveKey : suffixReverseKey;
            String selectedReverseKey = nextBoolean ? suffixReverseKey : suffixPositiveKey;

            // 存储正投和反投的账户列表
            List<String> positiveSelection = new ArrayList<>();
            List<String> reverseSelection = new ArrayList<>();

            // 遍历该位置的所有投项并进行分配
            for (String pos : positionList) {
                // 为正投选择账户
                positiveAccounts.forEach(account -> {
                    if (pos.endsWith(selectedPositiveKey)) {
                        positiveSelection.add(account.getAccount());
                    }
                });

                // 为反投选择账户
                reverseAccounts.forEach(account -> {
                    if (pos.endsWith(selectedReverseKey)) {
                        reverseSelection.add(account.getAccount());
                    }
                });
            }

            // 如果正投选择不为空，则记录该选择
            if (!positiveSelection.isEmpty()) {
                positiveSelections.put(basePosition + selectedPositiveKey, positiveSelection);
            }

            // 如果反投选择不为空，则记录该选择
            if (!reverseSelection.isEmpty()) {
                reverseSelections.put(basePosition + selectedReverseKey, reverseSelection);
            }

            // 标记该基础位置已分配
            allocatedPositions.add(basePosition);
        }
    }


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
