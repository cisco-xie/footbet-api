package com.example.demo.controller;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.example.demo.api.ConfigAccountService;
import com.example.demo.api.WebsiteService;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.enmu.WebsiteType;
import com.example.demo.common.enmu.XinBaoOddsFormatType;
import com.example.demo.common.utils.WebDriverFactory;
import com.example.demo.config.HttpProxyConfig;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.core.result.Result;
import com.example.demo.core.support.BaseController;
import com.example.demo.model.dto.AdminLoginDTO;
import com.example.demo.model.vo.ConfigAccountVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.devtools.DevTools;
import org.openqa.selenium.devtools.v114.network.Network;
import org.openqa.selenium.devtools.v114.network.model.CookieSameSite;
import org.openqa.selenium.devtools.v114.network.model.CookieSourceScheme;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/proxy")
public class ProxyController extends BaseController {

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
        return "pctag=48737fbc-cfb0-4199-b54b-32a6a57fc64e; dgRH0=6Zn5gZ2NOAamUly; skin=ps3838; b-user-id=cea40892-825f-666a-cf0e-a8fe24c39a01; _gid=GA1.2.1677352228.1736944373; _ga=GA1.1.2032622592.1750933442; PCTR=1896783056883; u=" + lcu + "; lcu=" + lcu + "; custid=" + custid + "; BrowserSessionId=" + browser + "; _og=QQ==; _ulp=KzhkT2JESFJ1US9xbC9rZkxDaHJZb3V2YVZlTCtKN2ViYnBYdGNCY0U2SzB4bnZpTVZaQWVwamhPQW5FSkNqS3xiOGZmZmEzZGNlM2Y0MGJiMmRlNDE2ZTEwYTMzMWM3Mg==; uoc=be97830afef253f33d2a502d243b8c37; _userDefaultView=COMPACT; SLID=" + slid + "; auth=true; _sig=Icy1OV014TnpZeVl6RTROek0wTXpjNE5nOm5xd2hWTTZTdmJIVmluQ0k1TndvaWxMS2g6MTcxMzE1ODI0NDo3Mzc2OTk5MTY6bm9uZTo5Q0NFQTlvSVhE; _apt=9CCEA9oIXD; _ga_DXNRHBHDY9=GS1.1.1736944373.1.1.1736944383.50.0.1813848857; _ga_1YEJQEHQ55=GS1.1.1739016699.1.0.1739016745.14.0.0; _vid=3cdc158d8a079b8caa05594a23644c6d; __prefs=W251bGwsNCwxLDAsMSxudWxsLGZhbHNlLDAuMDAwMCx0cnVlLHRydWUsIl8zTElORVMiLDAsbnVsbCx0cnVlLHRydWUsZmFsc2UsZmFsc2UsbnVsbCxudWxsLHRydWVd; lang=zh_CN; _lastView=eyJoNjEwMDAwMDAxIjoiQ09NUEFDVCJ9";
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
    /*private void setCookies(WebDriver driver, String cookie, String siteUrl) throws URISyntaxException {
        log.info("🍪 准备跳转网站 [{}] 以设置 Cookie", siteUrl);
        StringTokenizer st = new StringTokenizer(cookie, ";");
        driver.get(siteUrl);
        new WebDriverWait(driver, Duration.ofSeconds(15)).until(
                wd -> ((JavascriptExecutor) wd).executeScript("return document.readyState").equals("complete"));

        String currentHost = new URI(driver.getCurrentUrl()).getHost();
        log.info("🍪 网站跳转成功，当前页面 URL: {}", driver.getCurrentUrl());
        // 清除旧Cookie避免冲突
        driver.manage().deleteAllCookies();
        log.info("🍪 旧 Cookie 清除完成");
        int cookieCount = 0;
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            int idx = token.indexOf("=");
            if (idx > 0) {
                String name = token.substring(0, idx);
                String value = token.substring(idx + 1);
                try {
                    Cookie seleniumCookie = new Cookie.Builder(name, value)
                            .domain(currentHost)     // 使用从 URL 提取的域名
                            .path("/")          // 确保 Cookie 在根路径下有效
                            .isSecure(true)     // 设置为 Secure，确保 HTTPS 环境下有效
                            .sameSite("None")   // 允许跨域 Cookie
                            .build();
                    driver.manage().addCookie(seleniumCookie);
                    cookieCount++;
                    log.info("🍪 添加 Cookie 成功: {}={}", name, value);
                } catch (Exception e) {
                    log.info("⚠️ 添加 Cookie 失败: {}={}. 错误: {}", name, value, e.getMessage());
                }
            }
        }
        log.info("🍪 共添加 Cookie {} 个", cookieCount);
    }*/

    private void setCookies(WebDriver driver, String cookie, String siteUrl) throws URISyntaxException {
        log.info("🍪 准备跳转网站 [{}] 以设置 Cookie", siteUrl);
        StringTokenizer st = new StringTokenizer(cookie, ";");
        driver.get(siteUrl);

        new WebDriverWait(driver, Duration.ofSeconds(15)).until(
                wd -> ((JavascriptExecutor) wd).executeScript("return document.readyState").equals("complete"));

        String currentHost = new URI(driver.getCurrentUrl()).getHost();
        log.info("🍪 网站跳转成功，当前页面 URL: {}", driver.getCurrentUrl());
        log.info("🍪 当前页面 Host: {}", currentHost);

        driver.manage().deleteAllCookies();
        log.info("🍪 旧 Cookie 清除完成");

        // 使用 DevTools 添加 cookie
        DevTools devTools = ((ChromeDriver) driver).getDevTools();
        devTools.createSession();
        devTools.send(Network.enable(Optional.empty(), Optional.empty(), Optional.empty()));

        int cookieCount = 0;
        while (st.hasMoreTokens()) {
            String token = st.nextToken().trim();
            int idx = token.indexOf("=");
            if (idx > 0) {
                String name = token.substring(0, idx);
                String value = token.substring(idx + 1);
                try {
                    Boolean success = devTools.send(Network.setCookie(
                            name,
                            value,
                            Optional.empty(),                  // url
                            Optional.of(currentHost),         // domain
                            Optional.of("/"),                 // path
                            Optional.of(true),                // secure
                            Optional.of(false),               // httpOnly
                            Optional.of(CookieSameSite.NONE),// sameSite
                            Optional.empty(),                 // expires (TimeSinceEpoch),如果有过期时间需要构造TimeSinceEpoch对象
                            Optional.empty(),                 // priority
                            Optional.empty(),                 // sameParty
                            Optional.of(CookieSourceScheme.SECURE), // sourceScheme
                            Optional.of(443),                 // sourcePort
                            Optional.empty()                  // partitionKey
                    ));
                    devTools.send(Network.setBlockedURLs(List.of("*.png", "*.jpg", "*.jpeg", "*.gif", "*.woff", "*.svg", "*.ttf", "*.otf", "*.webp")));
                    if (Boolean.TRUE.equals(success)) {
                        cookieCount++;
                        log.info("🍪 添加 Cookie 成功: {}={}", name, value);
                    } else {
                        log.warn("⚠️ DevTools 设置 Cookie 失败: {}={}", name, value);
                    }
                } catch (Exception e) {
                    log.warn("⚠️ DevTools 添加 Cookie 异常: {}={}. 错误: {}", name, value, e.getMessage());
                }
            }
        }
        log.info("🍪 共添加 Cookie {} 个", cookieCount);
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
     * 从 URL 中提取域名（去除子域部分）
     * 例如：从 https://www.ps3838.com 提取 www.ps3838.com
     */
    private String extractHostFromUrl(String siteUrl) {
        try {
            URI uri = new URI(siteUrl);
            return uri.getHost(); // eg: www.ps3838.com
        } catch (URISyntaxException e) {
            throw new RuntimeException("URL 解析失败: " + siteUrl, e);
        }
    }

    /**
     * 等待页面加载完成
     * @param driver
     */
    private void waitForPageToLoad(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10)); // 控制等待时间

        // 先截图当前页面，方便调试
        try {
            // Thread.sleep(5000); // 页面 JS 渲染用一点时间
            File initialScreenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Files.copy(initialScreenshot.toPath(), Paths.get("/tmp/screenshot-before-wait.png"), StandardCopyOption.REPLACE_EXISTING);
            log.info("初始截图保存到 /tmp/screenshot-before-wait.png");
        } catch (IOException ioe) {
            log.warn("初始截图失败: {}", ioe.getMessage());
        }

        try {
            boolean success = wait.until(d -> {
                try {
                    String bodyText = driver.findElement(By.tagName("body")).getText();
                    if (bodyText.contains("会话超时,请重新登录") || bodyText.contains("请重新登录")) {
                        log.warn("⚠️ 页面疑似未登录，跳转失败");
                        throw new BusinessException(SystemError.USER_1016);
                    }

                    List<WebElement> dialogs = d.findElements(By.className("ui-dialog"));
                    if (!dialogs.isEmpty()) {
                        log.warn("⚠️ 检测到登录弹窗，需重新登录");
                        throw new BusinessException(SystemError.USER_1016);
                    }

                    // 表格加载判断
                    List<WebElement> trs = d.findElements(By.tagName("tr"));
                    if (trs.isEmpty()) {
                        String source = d.getPageSource();
                        log.info("⏳ 页面尚未加载表格 <tr> 元素，当前 HTML 长度: {}", source.length());
                        if (source.length() < 100) {
                            log.warn("⚠️ 页面内容太短（{}），疑似加载失败", bodyText.length());
                            throw new TimeoutException("页面内容太短");
                        }
                        return false;
                    }

                    return true;
                } catch (BusinessException be) {
                    throw be; // 立即抛出中止等待
                } catch (Exception e) {
                    log.warn("等待页面加载异常: {}", e.getMessage());
                    return false;
                }
            });

            if (success) {
                log.info("✅ 页面加载完成，表格已就绪");
                // 页面加载成功后，再截图一张
                try {
                    File successScreenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                    Files.copy(successScreenshot.toPath(), Paths.get("/tmp/screenshot-after-wait.png"), StandardCopyOption.REPLACE_EXISTING);
                    log.info("成功加载后截图保存到 /tmp/screenshot-after-wait.png");
                } catch (IOException ioe) {
                    log.warn("成功加载后截图失败: {}", ioe.getMessage());
                }
            }

        } catch (TimeoutException e) {
            // 超时了，截图当前状态
            try {
                File timeoutScreenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
                Files.copy(timeoutScreenshot.toPath(), Paths.get("/tmp/screenshot-timeout.png"), StandardCopyOption.REPLACE_EXISTING);
                log.info("超时截图保存到 /tmp/screenshot-timeout.png");
            } catch (IOException ioe) {
                log.warn("超时截图失败: {}", ioe.getMessage());
            }

            String pageSource = driver.getPageSource();
            log.warn("❌ 页面加载超时，HTML 长度: {}", pageSource.length());
            throw new TimeoutException("页面加载超时");
        }
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
        ConfigAccountVO account = accountService.getAccountById(admin.getUsername(), websiteId, accountId);
        if (account.getIsTokenValid() == 0) {
            throw new BusinessException(SystemError.USER_1016);
        }
        WebDriver driver = WebDriverFactory.createNewWebDriver(); // 从配置中获取共享实例

        try {
            // 根据 websiteId 判断执行不同的方法
            if (WebsiteType.PINGBO.getId().equals(websiteId)) {
                // return Result.success(proxySeleniumForWebsitePingBo(admin, websiteId, account, baseUrl, driver));
                return Result.success(extractBetsFromHtml(fetchPingBoHtml(account, baseUrl)));
            } else if (WebsiteType.XINBAO.getId().equals(websiteId)) {
                return Result.success(proxySeleniumForWebsiteXinBao(admin, websiteId, account, baseUrl, driver));
            } else if (WebsiteType.ZHIBO.getId().equals(websiteId)) {
                return Result.success(proxySeleniumForWebsiteZhiBo(admin, websiteId, account, baseUrl, driver));
            } else {
                throw new RuntimeException("未知的网站");
            }
        } finally {
            // ✅ 无论成功或异常，都关闭资源
            driver.quit();
        }
    }

    /**
     * 抓取平博未结注单页面，Hutool + Jsoup 抓取方式
     * @param account
     * @param baseUrl
     * @return
     */
    public String fetchPingBoHtml(ConfigAccountVO account, String baseUrl) {
        try {
            log.info("🟢 [PingBo-Http] 开始抓取页面...");

            // 1. 构建 Cookie
            String cookie = buildCookie(account);

            // 2. 发起请求
            HttpRequest request = HttpRequest.get(baseUrl + "/zh-cn/account/my-bets-full")
                    .timeout(30_000)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/114.0 Safari/537.36")
                    .header("Cookie", cookie)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9");

            // 引入配置代理
            HttpProxyConfig.configureProxy(request, account);

            String html = request.execute().body();
            log.info("🟢 [PingBo-Http] 页面抓取成功，HTML 长度: {}", html.length());
            log.info("🟢 [PingBo-Http] 页面前300字: {}", html.substring(0, Math.min(300, html.length())));

            // 3. 校验是否未登录（关键逻辑）
            if (html.contains("请重新登录") || html.contains("会话超时") || html.contains("class=\"ui-dialog\"")) {
                log.warn("⚠️ [PingBo-Http] 页面疑似未登录，包含登录提示词");
                throw new BusinessException(SystemError.USER_1016);
            }

            // 4. Jsoup 解析 + 清理
            Document document = Jsoup.parse(html);

            document.select("form.form-inline").remove();
            document.select("div.truncated-currencies").remove();

            document.select("link[href^='/'], script[src^='/'], img[src^='/']").forEach(element -> {
                if (element.hasAttr("href")) {
                    element.attr("href", baseUrl + element.attr("href"));
                }
                if (element.hasAttr("src")) {
                    element.attr("src", baseUrl + element.attr("src"));
                }
            });

            return document.html();
        } catch (BusinessException be) {
            throw be; // 保留业务异常
        } catch (Exception e) {
            log.error("❌ [PingBo-Http] 抓取失败", e);
            throw new BusinessException(SystemError.SYS_500);
        }
    }

    /**
     * 提取平博投注记录中的表格数据为 JSON 数组
     * @param html 页面 HTML 内容
     * @return JSON 数组，包含每行投注记录
     */
    public static JSONArray extractBetsFromHtml(String html) {
        JSONArray bets = new JSONArray();
        Document doc = Jsoup.parse(html);

        // 查找表格
        Element table = doc.selectFirst("table:has(tr)");
        if (table == null) {
            log.warn("❌ 未找到含有 <tr> 的表格");
            return bets;
        }

        log.info("✅ 找到投注表格，准备提取数据...");

        // 处理表头
        Elements headers = table.select("thead tr th");
        List<String> headerNames = new ArrayList<>();
        for (Element th : headers) {
            String name = th.text().trim();
            headerNames.add(name);
        }

        log.info("📌 表头共 {} 个字段: {}", headerNames.size(), headerNames);

        // 提取表体
        Elements rows = table.select("tbody tr");
        log.info("📄 共检测到 {} 行投注记录", rows.size());

        for (int r = 0; r < rows.size(); r++) {
            Element row = rows.get(r);
            Elements tds = row.select("td");
            if (tds.isEmpty()) {
                log.warn("⚠️ 第 {} 行为空，跳过", r + 1);
                continue;
            }

            JSONObject bet = new JSONObject();
            for (int i = 0; i < tds.size(); i++) {
                String key = (i < headerNames.size()) ? headerNames.get(i) : "col" + i;
                String value = tds.get(i).text().trim();
                bet.set(key, value);
            }

            log.info("✅ 第 {} 行解析结果: {}", r + 1, bet.toString());
            bets.add(bet);
        }

        log.info("✅ 完成提取，共 {} 条投注记录", bets.size());
        return bets;
    }

    private String proxySeleniumForWebsitePingBo(AdminLoginDTO admin, String websiteId, ConfigAccountVO account, String baseUrl, WebDriver driver) throws Exception {
        try {
            log.info("🟢 [PingBo] 开始抓取【平博】投注记录，用户：{}，账号：{}", admin.getUsername(), account.getAccount());
            log.info("🟢 [PingBo] Base URL: {}", baseUrl);

            // 构建完整的cookie字符串
            String cookie = buildCookie(account);
            log.info("🟢 [PingBo] 构建 Cookie 完成: {}", cookie);

            // 设置 Cookie
            setCookies(driver, cookie, baseUrl);
            log.info("🟢 [PingBo] 设置 Cookie 完成，准备跳转页面");

            // 导航到目标页面并等待加载完成
            int retries = 3;
            while (retries > 0) {
                try {
                    String targetUrl = baseUrl + "/zh-cn/account/my-bets-full";
                    log.info("🟢 [PingBo] 第 {} 次请求页面: {}", 4 - retries, targetUrl);
                    driver.get(targetUrl);
                    log.info("🟢 [PingBo] 页面跳转成功，开始等待页面加载");
                    log.info("🟢 [PingBo] 当前页面实际 URL：{}", driver.getCurrentUrl());
                    Thread.sleep(300); // 加一点缓冲，避免“空白页”
                    log.info("🟢 [PingBo] 页面 HTML 长度: {}", driver.getPageSource().length());
                    waitForPageToLoad(driver);
                    log.info("🟢 [PingBo] 页面标题：{}", driver.getTitle());
                    break; // 成功则退出循环
                } catch (TimeoutException e) {
                    retries--;
                    log.warn("❌️ [PingBo] 页面加载超时，重试次数剩余: {}", retries);
                    if (retries == 0) {
                        throw new BusinessException(SystemError.UNSETTLE_1330);
                    }
                } catch (Exception e) {
                    log.info("加载页面时发生异常: ", e);
                }
            }

            // 获取页面源代码
            String pageSource = driver.getPageSource();
            log.info("🟢 [PingBo] 页面加载完成，源码长度: {}", pageSource.length());
            log.info("🟢 [PingBo] 页面前300字: {}", pageSource.substring(0, Math.min(300, pageSource.length())));

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
        } catch (Exception e) {
            log.info("❌ [PingBo] 页面抓取失败", e);
            throw new BusinessException(SystemError.SYS_500);
        }
    }

    private String proxySeleniumForWebsiteXinBao(AdminLoginDTO admin, String websiteId, ConfigAccountVO account, String baseUrl, WebDriver driver) throws Exception {
        try {
            // 获取 serverresponse 对象，避免多次重复访问
            JSONObject serverResponse = account.getToken().getJSONObject("serverresponse");

            // 使用 String.format 进行格式化拼接 URL
            String url = String.format("%s/?cu=N&cuipv6=N&ipv6=N&uid=%s&pay_type=%s&username=%s&passwd_safe=%s&mid=%s&ltype=%s&currency=%s&odd_f=%s&domain=%s&blackBoxStatus=%s&odd_f_type=%s&timetype=sysTime&four_pwd=new&abox4pwd_notshow=N&msg=&langx=zh-cn&iovationCnt=1",
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
                    serverResponse.getStr("blackBoxStatus"),
                    XinBaoOddsFormatType.RM.getCurrencyCode()
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
                try {
                    // 尝试直接点击
                    betRecordButton.click();
                } catch (ElementClickInterceptedException e) {
                    log.info("新二网站出现遮挡");
                    // 处理遮挡情况
                    handlePopups(driver);
                    // 再次尝试点击
                    new WebDriverWait(driver, Duration.ofSeconds(3))
                            .until(ExpectedConditions.elementToBeClickable(betRecordButton))
                            .click();
                }

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
        }
    }

    /**
     * 处理新二页面遮挡元素
     * 通用弹窗处理方案（支持多种弹窗类型）
     * 处理多种弹窗类型（mask/popup/ann等） 6+种动态策略（含智能降级）
     * 不确定弹窗类型/未来可能新增弹窗 执行效率 中（需尝试多种策略）
     */
    private void handlePopups(WebDriver driver) {
        // 1. 定义所有可能的关闭方式（按优先级排序）
        List<By> closeStrategies = Arrays.asList(
                // 标准关闭按钮选择器
                By.cssSelector("#ann [id^='close_btn'], #ann .btn_gray_full, #ann .close"), // 以close_btn开头或特定class
                By.cssSelector("[class*='mask'] [class*='close'], [class*='popup'] [class*='close']"), // 通用弹窗关闭按钮
                By.cssSelector("button:contains('确认'), button:contains('关闭'), button:contains('知道了')"), // 文本匹配

                // 如果找不到按钮，尝试点击弹窗外的遮罩层
                By.cssSelector("div[class*='mask'][style*='display: block']")
        );

        // 2. 尝试各种关闭策略
        for (By strategy : closeStrategies) {
            try {
                List<WebElement> closeElements = driver.findElements(strategy);
                for (WebElement el : closeElements) {
                    if (el.isDisplayed()) {
                        try {
                            el.click();
                            log.info("使用策略关闭弹窗: " + strategy);
                            Thread.sleep(300); // 等待弹窗消失动画
                            return;
                        } catch (Exception e) {
                            continue; // 尝试下一个元素
                        }
                    }
                }
            } catch (Exception e) {
                continue; // 尝试下一个策略
            }
        }

        // 3. 最终回退方案
        try {
            // 3.1 尝试通过ESC键关闭
            driver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE);
            Thread.sleep(300);

            // 3.2 使用JS移除所有遮挡元素
            ((JavascriptExecutor)driver).executeScript(
                    "document.querySelectorAll('div[class*=\"mask\"], div[class*=\"popup\"]').forEach(el => {"
                            + "el.style.transition = 'opacity 0.3s';"
                            + "el.style.opacity = '0';"
                            + "setTimeout(() => el.remove(), 300);"
                            + "});"
            );

            log.info("使用强制方式关闭弹窗");
        } catch (Exception e) {
            log.info("所有弹窗关闭策略均失败", e);
        }
    }

    /**
     * 处理新二页面遮挡元素
     * 仅处理特定#ann弹窗 3种固定策略
     * 确定只有#ann弹窗 执行效率 高（无多余尝试）
     */
    private void handleObstructionEasy(WebDriver driver) {
        try {
            // 方案1：尝试关闭公告蒙层（如果有关闭按钮）
            List<WebElement> closeButtons = driver.findElements(By.cssSelector("#ann .close, #ann .btn-close"));
            if (!closeButtons.isEmpty()) {
                closeButtons.get(0).click();
                return;
            }

            // 方案2：等待蒙层自动消失
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .until(ExpectedConditions.invisibilityOfElementLocated(By.id("ann")));

            // 方案3：如果仍然存在，使用JS移除
            ((JavascriptExecutor)driver).executeScript(
                    "const ann = document.getElementById('ann');" +
                            "if(ann) ann.style.display = 'none';");
        } catch (Exception e) {
            log.warn("处理遮挡元素时出现异常", e);
        }
    }

    private String proxySeleniumForWebsiteZhiBo(AdminLoginDTO admin, String websiteId, ConfigAccountVO account, String baseUrl, WebDriver driver) throws Exception {
        try {
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
        }
    }

    /**
     * 检查页面是否已经加载了 data-list 元素
     */
    private boolean isDataListLoaded(WebDriver driver, WebDriverWait wait) {
        try {
            log.info("等待 URL 包含 'betList'...");
            wait.until(ExpectedConditions.urlContains("betList"));

            String currentUrl = driver.getCurrentUrl();
            log.info("当前 URL: {}", currentUrl);

            log.info("等待页面出现 data-list 元素...");
            wait.until(ExpectedConditions.presenceOfElementLocated(By.className("data-list")));

            log.info("页面加载成功 ✅");
            return true;
        } catch (TimeoutException e) {
            String currentUrl = driver.getCurrentUrl();
            String source = driver.getPageSource();
            String preview = source.length() > 300 ? source.substring(0, 300) : source;

            log.info("页面加载失败 ❌，当前 URL: {}", currentUrl);
            log.info("页面源码前300字:\n{}", preview);

            // isn88 可能提示 IP 限制、代理拦截、需要重新登录
            if (source.contains("请重新登录") || source.contains("非法访问") || source.contains("访问受限") || currentUrl.contains("error")) {
                log.info("isn88 页面提示错误或跳转异常，疑似被拦截");
            }

            return false;
        } catch (Exception ex) {
            log.info("加载 isn88 页面时出现异常", ex);
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
