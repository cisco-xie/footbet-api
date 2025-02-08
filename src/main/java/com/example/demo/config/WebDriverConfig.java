package com.example.demo.config;

import jakarta.annotation.PreDestroy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebDriver 初始化 全局的单例模式
 */
@Configuration
public class WebDriverConfig {

    // 使用 ThreadLocal 来存储每个线程的 WebDriver 实例
    private static final ThreadLocal<WebDriver> driverThreadLocal = new ThreadLocal<>();

    @Bean
    public WebDriver webDriver() {
        // 检查当前线程是否已经有 WebDriver 实例
        if (driverThreadLocal.get() == null) {
            System.setProperty("webdriver.chrome.driver", "D:\\developer\\chromedriver-v133\\chromedriver.exe");
            // 配置Chrome选项以启用无头模式
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--remote-allow-origins=*");
            options.addArguments("--blink-settings=imagesEnabled=false"); // 禁止加载图片
            WebDriver driver = new ChromeDriver(options);
            // driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
            // 将 WebDriver 实例存储在 ThreadLocal 中
            driverThreadLocal.set(driver);
        }
        // 返回当前线程的 WebDriver 实例
        return driverThreadLocal.get();
    }

    /**
     * 服务关闭时自动清理 WebDriver 实例
     */
    @PreDestroy
    public void cleanup() {
        if (driverThreadLocal.get() != null) {
            driverThreadLocal.get().quit(); // 关闭 WebDriver 实例，释放资源
            driverThreadLocal.remove();     // 清除线程局部存储
        }
    }

}
