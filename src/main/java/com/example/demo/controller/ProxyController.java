package com.example.demo.controller;

import cn.hutool.json.JSONObject;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v132.network.Network;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.*;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/proxy")
public class ProxyController extends BaseController {

    @Resource
    private WebDriver webDriver;

    @Resource
    private ConfigAccountService accountService;

    @Resource
    private WebsiteService websiteService;

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
     * 构建 新宝网站 Cookie 参数
     * @param account
     * @return
     */
    private String buildCookieXinBao(ConfigAccountVO account) {
        return "accept=48737fbc-cfb0-4199-b54b-32a6a57fc64e;";
    }

    /**
     * 设置 Cookies 方法
     * @param driver
     * @param cookie
     */
    private void setCookies(WebDriver driver, String cookie, String siteUrl) {
        StringTokenizer st = new StringTokenizer(cookie, ";");
        driver.get(siteUrl);
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            int idx = token.indexOf("=");
            if (idx > 0) {
                String name = token.substring(0, idx);
                String value = token.substring(idx + 1);
                // 提取根域名，确保跨子域有效
                String domain = extractDomainFromUrl(siteUrl);

                Cookie seleniumCookie = new Cookie.Builder(name, value)
                        .domain(domain)     // 使用从 URL 提取的域名
                        .path("/")          // 确保 Cookie 在根路径下有效
                        .isSecure(true)     // 设置为 Secure，确保 HTTPS 环境下有效
                        .sameSite("None")   // 允许跨域 Cookie
                        .build();
                driver.manage().addCookie(seleniumCookie);
            }
        }
    }

    /**
     * 从 URL 中提取域名（去除子域部分）
     * 例如：从 https://www.ps3838.com 提取 ps3838.com
     */
    private String extractDomainFromUrl(String siteUrl) {
        try {
            URI uri = new URI(siteUrl);
            String host = uri.getHost();
            // 提取主域名，去掉 www 等子域部分
            String[] domainParts = host.split("\\.");
            if (domainParts.length > 2) {
                return domainParts[domainParts.length - 2] + "." + domainParts[domainParts.length - 1];
            }
            return host;  // 如果域名没有子域，则直接返回主域名
        } catch (URISyntaxException e) {
            throw new RuntimeException("Invalid URL syntax", e);
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
                throw new BusinessException(SystemError.USER_1016);
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

    private void waitForPageToLoadXinBao(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
        wait.until(d -> {
            // 检查是否存在登录弹窗（class="ui-dialog" 的 <div> 元素）
            List<WebElement> dialogElements = d.findElements(By.id("alert_kick"));
            if (!dialogElements.isEmpty()) {
                // 如果找到了 alert_kick，则说明需要重新登录
                System.out.println("检测到登录弹窗，触发重新登录");
                // 处理重新登录的逻辑，抛出异常或其他处理
                throw new BusinessException(SystemError.USER_1006);
            }

            // 检查页面中是否已经加载了 box_header 元素
            return ExpectedConditions.presenceOfElementLocated(By.className("box_header"));
        });
    }

    @GetMapping("/selenium")
    public Result proxySeleniumUnsettled(@RequestParam String websiteId, @RequestParam String accountId) throws Exception {
        AdminLoginDTO admin = getUser();
        String baseUrl = websiteService.getWebsiteBaseUrl(admin.getUsername(), websiteId);
        WebDriver driver = webDriver; // 从配置中获取共享实例

        // 根据 websiteId 判断执行不同的方法
        if (WebsiteType.PINGBO.getId().equals(websiteId)) {
            return Result.success(proxySeleniumForWebsitePingBo(admin, websiteId, accountId, baseUrl, driver));
        } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
            return Result.success(proxySeleniumForWebsiteXinBao(admin, websiteId, accountId, baseUrl, driver));
        } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
            return Result.success(proxySeleniumForWebsiteZhiBo(admin, websiteId, accountId, baseUrl, driver));
        } else {
            throw new RuntimeException("未知的网站");
        }
    }

    private String proxySeleniumForWebsitePingBo(AdminLoginDTO admin, String websiteId, String accountId, String baseUrl, WebDriver driver) throws Exception {
        try {
            ConfigAccountVO account = accountService.getAccountById(admin.getUsername(), websiteId, accountId);

            // 构建完整的cookie字符串
            String cookie = buildCookie(account);

            // 设置 Cookie
            setCookies(driver, cookie, baseUrl);

            // 导航到目标页面并等待加载完成
            int retries = 3;
            while (retries > 0) {
                try {
                    driver.get(baseUrl + "/zh-cn/account/my-bets-full");
                    waitForPageToLoad(driver);
                    break; // 成功则退出循环
                } catch (TimeoutException e) {
                    retries--;
                    if (retries == 0) {
                        throw new BusinessException(SystemError.UNSETTLE_1330);
                    }
                    log.info("页面加载超时，剩余重试次数: " + retries);
                }
            }

            // 获取页面源代码
            String pageSource = driver.getPageSource();
            // 解析 HTML
            Document document = Jsoup.parse(pageSource);

            // 删除不需要的元素
            document.select("form.form-inline").remove();
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
            // 关闭 WebDriver（根据需要可选择注释掉）
            // driver.quit();
        }
    }

    private String proxySeleniumForWebsiteXinBao(AdminLoginDTO admin, String websiteId, String accountId, String baseUrl, WebDriver driver) throws Exception {
        try {
            ConfigAccountVO account = accountService.getAccountById(admin.getUsername(), websiteId, accountId);

            // 获取 serverresponse 对象，避免多次重复访问
            JSONObject serverResponse = account.getToken().getJSONObject("serverresponse");

            // 使用 String.format 进行格式化拼接 URL
            String url = String.format("%s/?cu=N&cuipv6=N&ipv6=N&uid=%s&pay_type=%s&username=%s&passwd_safe=%s&mid=%s&ltype=%s&currency=%s&odd_f=%s&domain=%s&blackBoxStatus=%s&odd_f_type=H&timetype=sysTime&four_pwd=new&abox4pwd_notshow=N&msg=&langx=zh-cn&iovationCnt=1",
                    baseUrl,
                    serverResponse.getStr("uid"),
                    serverResponse.getStr("pay_type"),
                    serverResponse.getStr("username"),
                    serverResponse.getStr("passwd_safe"),
                    serverResponse.getStr("mid"),
                    serverResponse.getStr("ltype"),
                    serverResponse.getStr("currency"),
                    serverResponse.getStr("odd_f"),
                    serverResponse.getStr("domain"),
                    serverResponse.getStr("blackBoxStatus")
            );
            // 导航到根域名页面
            driver.get(url);

            // 等待页面加载完成，直到 'box_header' 元素可见
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete'"));
            // 检查是否有需要重新登录的提示
            try {
                WebElement alertKickElement = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("alert_kick")));
                // 检查该元素的 class 是否包含 'on'，表示需要重新登录
                if (alertKickElement != null && alertKickElement.getAttribute("class").contains("on")) {
                    throw new BusinessException(SystemError.USER_1016);
                }
            } catch (TimeoutException e) {
                // 如果超时，说明没有 alert_kick 元素，继续等待其他页面元素
                log.info("没有检测到需要重新登录的提示");
            }
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("box_header")));

            // 找到“投注记录”按钮并点击
            WebElement betRecordButton = driver.findElement(By.id("header_todaywagers"));
            if (betRecordButton != null) {
                betRecordButton.click();

                wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete'"));
                // 等待目标页面加载完成，直到 'all_outside' 元素可见
                wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("all_outside")));

                // 获取页面源代码
                String pageSource = driver.getPageSource();
                // 解析 HTML
                Document document = Jsoup.parse(pageSource);

                // 隐藏不需要的元素，删除会导致样式错乱
                document.select("#header_show").attr("style", "display: none;");
                document.select("ul.tool_category").attr("style", "display: none;");
                // 删除不需要的元素
                document.select("div.tool_selectbox").remove();
                document.select("#right_show").remove();
                document.select("#footer_relating_box").remove();
                document.select("#bottom_show").remove();
                document.select("div.content_footer").remove();
                document.select("div.box_copyright").remove();

                // 返回修改后的页面源代码
                return document.html();
            } else {
                log.warn("未找到 '投注记录' 按钮");
                throw new BusinessException(SystemError.UNSETTLE_1330);
            }
        } catch (NoSuchElementException e) {
            log.warn("页面元素未找到");
            throw new BusinessException(SystemError.UNSETTLE_1330);
        } catch (TimeoutException e) {
            log.warn("页面加载超时");
            throw new BusinessException(SystemError.UNSETTLE_1330);
        } finally {
            // 根据需要关闭 WebDriver，这里可以根据实际需求决定是否保留 WebDriver
            // driver.quit();
        }
    }

    private String proxySeleniumForWebsiteZhiBo(AdminLoginDTO admin, String websiteId, String accountId, String baseUrl, WebDriver driver) throws Exception {
        try {
            // 获取账号信息
            ConfigAccountVO account = accountService.getAccountById(admin.getUsername(), websiteId, accountId);
            String username = account.getAccount();  // 账号
            String password = account.getPassword();  // 密码

            // 构造目标 URL
            String url = String.format("%s/membersite-api/#/betList", baseUrl);

            // 初始化 WebDriverWait
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            // 清除 cookies，确保每次请求的浏览器都是干净的
            driver.manage().deleteAllCookies();
            // 检查页面是否已经加载了 data-list 元素
            if (isDataListLoaded(driver, wait)) {
                // 检查是否有弹窗提示重新登录
                WebElement modalContainer = null;
                try {
                    // 等待 modal 弹窗出现
                    modalContainer = wait.until(ExpectedConditions.presenceOfElementLocated(By.className("modal-container")));
                    WebElement alertText = modalContainer.findElement(By.cssSelector(".app-alert span"));
                    if (alertText.getText().contains("请重新登录")) {
                        // 弹窗存在并包含"请重新登录"信息，点击确认按钮
                        WebElement confirmButton = modalContainer.findElement(By.cssSelector(".btn-cancel"));
                        confirmButton.click();
                        log.info("点击确认按钮，跳转到登录页面");
                    }
                } catch (TimeoutException modalException) {
                    // 没有弹窗，继续执行后续操作
                    log.info("没有检测到重新登录的弹窗");
                    return getPageSourceAndCleanHtml(driver, baseUrl);
                }
            }

            // 如果未找到 data-list 元素，执行登录流程
            log.info("未找到 data-list 元素，开始执行登录流程");

            // 导航到登录页面
            driver.get(url);

            // 等待并输入用户名和密码
            wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("input[type='text']")));  // 等待用户名输入框加载
            WebElement usernameField = driver.findElement(By.cssSelector("input[type='text']"));
            WebElement passwordField = driver.findElement(By.id("pwd"));
            usernameField.sendKeys(username);
            passwordField.sendKeys(password);

            // 点击登录按钮
            WebElement loginButton = driver.findElement(By.cssSelector("button[type='button']"));
            wait.until(ExpectedConditions.elementToBeClickable(loginButton));  // 等待登录按钮可点击
            loginButton.click();

            // 等待登录完成并检查 data-list 元素
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("data-list")));

            // 返回处理后的页面源码
            return getPageSourceAndCleanHtml(driver, baseUrl);
        } catch (NoSuchElementException e) {
            log.warn("页面元素未找到", e);
            throw new BusinessException(SystemError.UNSETTLE_1330);
        } catch (TimeoutException e) {
            log.warn("页面加载超时", e);
            throw new BusinessException(SystemError.UNSETTLE_1330);
        } catch (Exception e) {
            log.error("登录或页面加载失败", e);
            throw new BusinessException(SystemError.UNSETTLE_1330);
        } finally {
            // 根据需要关闭 WebDriver
            // driver.quit();
        }
    }

    /**
     * 检查页面是否已经加载了 data-list 元素
     */
    private boolean isDataListLoaded(WebDriver driver, WebDriverWait wait) {
        try {
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("data-list")));
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * 获取页面源码并清理 HTML
     */
    private String getPageSourceAndCleanHtml(WebDriver driver, String baseUrl) {
        // 获取页面源码
        String pageSource = driver.getPageSource();

        // 解析 HTML
        Document document = Jsoup.parse(pageSource);

        // 删除不需要的元素
        document.select("#app_top, #app_left, #app_right, #app_footer, div.nav-other, div.ns-centered, #app_news").remove();

        // 修正静态资源路径
        document.select("link[href^='/'], script[src^='/'], img[src^='/']").forEach(element -> {
            if (element.hasAttr("href")) {
                element.attr("href", baseUrl + element.attr("href"));
            }
            if (element.hasAttr("src")) {
                element.attr("src", baseUrl + element.attr("src"));
            }
        });

        // 返回处理后的 HTML
        return document.html();
    }


}
