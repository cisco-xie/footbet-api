package com.example.demo.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.core.exception.BusinessException;
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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/proxy")
public class ProxyController extends BaseController {

    @Resource
    private WebDriver webDriver;

    @Resource
    private ConfigAccountService accountService;

    /**
     * 构建 Cookie 参数
     * @param account
     * @return
     */
    private String buildCookie(ConfigAccountVO account) {
        String custid = account.getToken().getJSONObject("tokens").getStr("X-Custid");
        String browser = account.getToken().getJSONObject("tokens").getStr("X-Browser-Session-Id");
        String slid = account.getToken().getJSONObject("tokens").getStr("X-SLID");
        String lcu = account.getToken().getJSONObject("tokens").getStr("X-Lcu");
        return "pctag=48737fbc-cfb0-4199-b54b-32a6a57fc64e; dgRH0=6Zn5gZ2NOAamUly; skin=ps3838; b-user-id=cea40892-825f-666a-cf0e-a8fe24c39a01; _gid=GA1.2.1677352228.1736944373; _ga=GA1.2.1445030965.1736944373; PCTR=1896783056883; u=" + lcu + "; lcu=" + lcu + "; custid=" + custid + "; BrowserSessionId=" + browser + "; _og=QQ==; _ulp=KzhkT2JESFJ1US9xbC9rZkxDaHJZb3V2YVZlTCtKN2ViYnBYdGNCY0U2SzB4bnZpTVZaQWVwamhPQW5FSkNqS3xiOGZmZmEzZGNlM2Y0MGJiMmRlNDE2ZTEwYTMzMWM3Mg==; uoc=be97830afef253f33d2a502d243b8c37; _userDefaultView=COMPACT; SLID=" + slid + "; auth=true; _sig=Icy1OV014TnpZeVl6RTROek0wTXpjNE5nOm5xd2hWTTZTdmJIVmluQ0k1TndvaWxMS2g6MTcxMzE1ODI0NDo3Mzc2OTk5MTY6bm9uZTo5Q0NFQTlvSVhE; _apt=9CCEA9oIXD; _ga_DXNRHBHDY9=GS1.1.1736944373.1.1.1736944383.50.0.1813848857; _ga_1YEJQEHQ55=GS1.1.1739016699.1.0.1739016745.14.0.0; _vid=3cdc158d8a079b8caa05594a23644c6d; __prefs=W251bGwsNCwxLDAsMSxudWxsLGZhbHNlLDAuMDAwMCx0cnVlLHRydWUsIl8zTElORVMiLDAsbnVsbCx0cnVlLHRydWUsZmFsc2UsZmFsc2UsbnVsbCxudWxsLHRydWVd; lang=zh_CN; _lastView=eyJoNjEwMDAwMDAxIjoiQ09NUEFDVCJ9";
    }

    /**
     * 设置 Cookies 方法
     * @param driver
     * @param cookie
     */
    private void setCookies(WebDriver driver, String cookie) {
        StringTokenizer st = new StringTokenizer(cookie, ";");
        driver.get("https://www.ps3838.com");
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            int idx = token.indexOf("=");
            if (idx > 0) {
                String name = token.substring(0, idx);
                String value = token.substring(idx + 1);
                Cookie seleniumCookie = new Cookie.Builder(name, value)
                        .domain(".ps3838.com")
                        .path("/")
                        .isSecure(true)  // 设置为 Secure，确保 HTTPS 环境下有效
                        .sameSite("None")  // 允许跨域 Cookie
                        .build();
                driver.manage().addCookie(seleniumCookie);
            }
        }
    }

    /**
     * 等待页面加载完成
     * @param driver
     */
    private void waitForPageToLoad(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        wait.until(d -> {
            // 检查是否存在登录弹窗（class="ui-dialog" 的 <div> 元素）
            List<WebElement> dialogElements = d.findElements(By.className("ui-dialog"));
            if (!dialogElements.isEmpty()) {
                // 如果找到了 ui-dialog，则说明需要重新登录
                System.out.println("检测到登录弹窗，触发重新登录");
                // 处理重新登录的逻辑，抛出异常或其他处理
                throw new BusinessException(SystemError.USER_1006);
            }

            // 检查页面中是否已经加载了表格的第一个 <tr> 元素
            return ExpectedConditions.presenceOfElementLocated(By.tagName("tr")).apply(d) != null;
        });
//        wait.until(d -> {
//            try {
//                // 重新获取 table，防止 StaleElementReferenceException
//                WebElement table = d.findElement(By.className("info-div-table"));
//
//                // 使用 JS 获取表格内容
//                String tableContent = (String) ((JavascriptExecutor) d).executeScript("return arguments[0].innerHTML;", table);
//
//                System.out.println("当前表格内容：" + tableContent.trim());
//
//                // 确保表格有内容，并且内容至少包含一个 <tr> 元素
//                return tableContent.trim().length() > 10;
//            } catch (NoSuchElementException | StaleElementReferenceException e) {
//                return false; // 继续等待
//            }
//        });
    }



    @GetMapping("/selenium")
    public String proxyselenium(@RequestParam String url, @RequestParam String websiteId, @RequestParam String accountId) throws Exception {
        WebDriver driver = webDriver; // 从配置中获取共享实例

        try {
            AdminLoginDTO admin = getUser();
            ConfigAccountVO account = accountService.getAccountById(admin.getUsername(), websiteId, accountId);

            // 构建完整的cookie字符串
            String cookie = buildCookie(account);

            // 设置 Cookie
            setCookies(driver, cookie);

            // 导航到目标页面并等待加载完成
            int retries = 3;
            while (retries > 0) {
                try {
                    driver.get(url);
                    waitForPageToLoad(driver);
                    break; // 成功则退出循环
                } catch (TimeoutException e) {
                    retries--;
                    if (retries == 0) {
                        throw new RuntimeException("页面加载超时，重试次数用尽", e);
                    }
                    System.out.println("页面加载超时，剩余重试次数: " + retries);
                }
            }
            // 获取页面源代码
            String pageSource = driver.getPageSource();
            // 解析 HTML
            Document document = Jsoup.parse(pageSource);

            String baseUrl = "https://www.ps3838.com";
            // 在 <head> 中插入 <base> 标签
            // document.head().prepend("<base href=\""+baseUrl+"\">");
            // 选择所有 class 为 "form-inline" 的 form 元素(这个是查询表单块)，并删除
            document.select("form.form-inline").remove();
            // 删除所有 class 为 "truncated-currencies" 的 <div> 元素
            document.select("div.truncated-currencies").remove();

            // 修正资源路径
            document.select("link[href^='/'], script[src^='/'], img[src^='/']").forEach(element -> {
                if (element.hasAttr("href")) {
                    element.attr("href", baseUrl + element.attr("href"));
                }
            });
            // 获取页面源代码
             return document.html();
        } finally {
            // 关闭WebDriver
             // driver.quit();
        }
    }

}
