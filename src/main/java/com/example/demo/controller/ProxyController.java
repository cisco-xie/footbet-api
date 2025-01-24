package com.example.demo.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.htmlunit.BrowserVersion;
import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.util.Cookie;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController extends BaseController {

    @Resource
    private WebDriver webDriver;

    @Resource
    private ConfigAccountService accountService;

    @GetMapping("/selenium")
    public String proxyselenium(@RequestParam String url, @RequestParam String websiteId, @RequestParam String accountId) throws Exception {
        WebDriver driver = webDriver; // 从配置中获取共享实例
        try {
            AdminLoginDTO admin = getUser();
            ConfigAccountVO account = accountService.getAccountById(admin.getUsername(), websiteId, accountId);

            // 构建完整的cookie字符串
            String custid = account.getToken().getJSONObject("tokens").getStr("X-Custid");
            String browser = account.getToken().getJSONObject("tokens").getStr("X-Browser-Session-Id");
            String slid = account.getToken().getJSONObject("tokens").getStr("X-SLID");
            String lcu = account.getToken().getJSONObject("tokens").getStr("X-Lcu");
            String cookie = "JSESSIONID=60080FF6CE15EA2EAE4212CCBE25C58E; pctag=48737fbc-cfb0-4199-b54b-32a6a57fc64e; dgRH0=6Zn5gZ2NOAamUly; skin=ps3838; b-user-id=86848c3d-24b8-fa15-e0c4-c26ae9df3b9a; _gid=GA1.2.1677352228.1736944373; _ga=GA1.2.1445030965.1736944373; PCTR=1894710782467; u=" + lcu + "; lcu=" + lcu + "; custid=" + custid + "; BrowserSessionId=" + browser + "; _og=QQ==; _ulp=KzhkT2JESFJ1US9xbC9rZkxDaHJZb3V2YVZlTCtKN2ViYnBYdGNCY0U2SzB4bnZpTVZaQWVwamhPQW5FSkNqS3xiOGZmZmEzZGNlM2Y0MGJiMmRlNDE2ZTEwYTMzMWM3Mg==; uoc=be97830afef253f33d2a502d243b8c37; _userDefaultView=COMPACT; SLID=" + slid + "; _sig=Tcy1OV014TnpZeVl6RTROek0wTXpjNE5nOjNjZWtOQmp0eUczZGhEVE5TcHZzYWVHRmU6MTcxMzE1ODI0NDo3MzY5NDQzODI6bm9uZTpXb2U1NlZ6M3Uw; _apt=Woe56Vz3u0; _ga_DXNRHBHDY9=GS1.1.1736944373.1.1.1736944383.50.0.1813848857; _ga_1YEJQEHQ55=GS1.1.1736944373.1.1.1736944383.50.0.0; _vid=dde4ede6a2ad88833c20148ab7cecb52; __prefs=W251bGwsMSwxLDAsMSxudWxsLGZhbHNlLDAuMDAwMCxmYWxzZSx0cnVlLCJfM0xJTkVTIiwxLG51bGwsdHJ1ZSx0cnVlLGZhbHNlLGZhbHNlLG51bGwsbnVsbCx0cnVlXQ==; lang=zh_CN; _lastView=eyJoNjEwMDAwMDAxIjoiQ09NUEFDVCJ9";

            // 分割cookie字符串并添加到WebDriver
            StringTokenizer st = new StringTokenizer(cookie, ";");
            driver.get("https://www.ps3838.com");
            while (st.hasMoreTokens()) {
                String token = st.nextToken().trim();
                int idx = token.indexOf("=");
                if (idx > 0) {
                    String name = token.substring(0, idx);
                    String value = token.substring(idx + 1);
                    org.openqa.selenium.Cookie seleniumCookie = new org.openqa.selenium.Cookie.Builder(name, value)
                            .domain(".ps3838.com")
                            .path("/")
                            .build();
                    driver.manage().addCookie(seleniumCookie);
                }
            }

            // 导航到目标网页
            driver.get(url);
            // 等待页面加载完成，这里使用Thread.sleep，也可以使用WebDriver的显式等待
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.presenceOfElementLocated(By.tagName("body")));

            // 获取页面源代码
            return driver.getPageSource().replaceAll("href=\"/", "href=\"https://www.ps3838.com/");
        } finally {
            // 关闭WebDriver
            // driver.quit();
        }
    }

}
