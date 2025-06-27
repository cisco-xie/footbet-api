package com.example.demo.common.utils;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public class WebDriverFactory {

    /**
     * 创建一个新的 WebDriver 实例，支持 Windows 和 Linux
     */
    public static WebDriver createNewWebDriver() {
        ChromeOptions options = new ChromeOptions();

        // === Chrome 参数配置 ===
        options.addArguments("--headless"); // 无头模式，服务器必须启用
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--remote-allow-origins=*");
        options.addArguments("--blink-settings=imagesEnabled=false"); // 禁图加速加载
        options.addArguments("--disable-software-rasterizer");

        // === 检测系统平台 ===
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            // Windows 系统配置
            System.setProperty("webdriver.chrome.driver", "D:\\developer\\chromedriver-v135\\chromedriver.exe");
        } else if (os.contains("linux")) {
            // Linux 系统配置
            System.setProperty("webdriver.chrome.driver", "/usr/local/chromedriver-linux64/chromedriver");
            options.setBinary("/usr/bin/google-chrome"); // 可选，如果 chrome 不在默认路径
        } else {
            throw new UnsupportedOperationException("不支持的操作系统: " + os);
        }

        // === 创建并返回实例 ===
        return new ChromeDriver(options);
    }
}

