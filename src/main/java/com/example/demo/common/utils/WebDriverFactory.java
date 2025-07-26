package com.example.demo.common.utils;

import com.example.demo.model.vo.ConfigAccountVO;
import org.apache.commons.lang3.StringUtils;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.List;
import java.util.UUID;

public class WebDriverFactory {

    /**
     * 创建一个新的 WebDriver 实例（支持代理配置）
     * @param userConfig 账号配置（包含代理信息）
     */
    public static WebDriver createNewWebDriver(ConfigAccountVO userConfig) {
        ChromeOptions options = buildBaseOptions();
        configureSystemProperties(options);

        // 配置代理（如果存在）
        if (needProxy(userConfig)) {
            options.setProxy(buildSeleniumProxy(userConfig));
        }

        return new ChromeDriver(options);
    }

    /**
     * 构建基础Chrome配置（复用原有参数）
     */
    private static ChromeOptions buildBaseOptions() {
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("excludeSwitches", List.of("enable-automation"));
        options.setExperimentalOption("useAutomationExtension", false);
        options.addArguments();
        options.addArguments(
                "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.5735.90 Safari/537.36",
                "--headless=new",              // 使用新版无头模式
                "--disable-gpu",
                "--window-size=1920,1080",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--remote-allow-origins=*",
                "--blink-settings=imagesEnabled=false",
                "--disable-software-rasterizer",
                "--disable-blink-features=AutomationControlled",  // 反自动化检测
                "--user-data-dir=/tmp/chrome-profile-" + UUID.randomUUID()
        );
        return options;
    }

    /**
     * 配置系统路径（根据操作系统）
     */
    private static void configureSystemProperties(ChromeOptions options) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            System.setProperty("webdriver.chrome.driver", "D:\\developer\\chromedriver-v135\\chromedriver.exe");
        } else if (os.contains("linux")) {
            // 可选：显式指定 chromedriver 路径（虽然已软链接到 /usr/local/bin）
            System.setProperty("webdriver.chrome.driver", "/usr/local/bin/chromedriver");
            options.setBinary("/usr/local/bin/google-chrome"); // 标准Linux安装路径
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }

    /**
     * 判断是否需要代理
     */
    private static boolean needProxy(ConfigAccountVO userConfig) {
        return userConfig != null &&
                userConfig.getProxyType() != null &&
                userConfig.getProxyType() != 0 &&
                StringUtils.isNotBlank(userConfig.getProxyHost());
    }

    /**
     * 构建Selenium代理配置
     */
    private static Proxy buildSeleniumProxy(ConfigAccountVO userConfig) {
        Proxy proxy = new Proxy();
        String proxyAddr = userConfig.getProxyHost() + ":" + userConfig.getProxyPort();

        // 判断代理类型并设置
        if (userConfig.getProxyType() == 1) {
            // HTTP/HTTPS 代理
            proxy.setHttpProxy(proxyAddr)
                    .setSslProxy(proxyAddr);
        } else if (userConfig.getProxyType() == 2) {
            // SOCKS5 代理
            proxy.setSocksProxy(proxyAddr)
                    .setSocksVersion(5);
        }

        // 如果代理带有用户名和密码（仅 SOCKS 代理支持）
        if (StringUtils.isNotBlank(userConfig.getProxyUsername())) {
            proxy.setSocksUsername(userConfig.getProxyUsername());
            proxy.setSocksPassword(userConfig.getProxyPassword());

            // 设置系统代理认证，用于部分场景（但 ChromeDriver 浏览器请求未必生效）
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            userConfig.getProxyUsername(),
                            userConfig.getProxyPassword().toCharArray()
                    );
                }
            });
        }

        return proxy;
    }


    /**
     * 保留原有无参方法（兼容旧代码）
     */
    public static WebDriver createNewWebDriver() {
        return createNewWebDriver(null);
    }

}

