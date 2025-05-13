package com.example.demo.config;

import jakarta.annotation.PreDestroy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebDriver 配置类，用于创建和管理 WebDriver 实例。
 * <p>
 * 本类使用 {@link ThreadLocal} 确保每个线程有独立的 WebDriver 实例，
 * 从而避免多线程环境下的会话冲突。通过这种方式，每个请求都可以拥有一个独立的 WebDriver 实例，适用于高并发场景，避免了全局共享实例带来的问题。
 * </p>
 *
 * <p>
 * WebDriver 实例配置为 ChromeDriver，并启用无头模式，适用于后台执行的自动化任务。
 * 在应用程序关闭时，WebDriver 实例会自动释放资源，以避免内存泄漏。
 * </p>
 */
@Configuration
public class WebDriverConfig {

    // 使用 ThreadLocal 来存储每个线程的 WebDriver 实例
    private static final ThreadLocal<WebDriver> driverThreadLocal = new ThreadLocal<>();

    @Bean
    public WebDriver webDriver() {
        // 检查当前线程是否已经有 WebDriver 实例
        if (driverThreadLocal.get() == null) {
            System.setProperty("webdriver.chrome.driver", "D:\\developer\\chromedriver-v135\\chromedriver.exe");
            // 配置Chrome选项以启用无头模式
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--disable-gpu");
            options.addArguments("--window-size=1920,1080");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--remote-allow-origins=*");
            options.addArguments("--blink-settings=imagesEnabled=false"); // 禁止加载图片
            options.addArguments("--disable-software-rasterizer"); // 加速渲染，防止崩溃

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
