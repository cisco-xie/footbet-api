package com.example.demo.task;

import com.example.demo.api.FalaliApi;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * @Description:
 * @Author: 谢诗宏
 * @Date: 2024年11月27日
 * @Version 1.0
 */
@Slf4j
@Component
public class AutoBetTask {

    @Resource
    private FalaliApi falaliApi;

    // @Scheduled(cron = "0/5 * * * * ?")
    // 上一次任务完成后再延迟 5 秒执行
    @Scheduled(fixedDelay = 10000)
    public void bet() {
        try {
            log.info("开始执行 自动下注...");
            falaliApi.autoBet();
        } catch (Exception e) {
            log.error("autoBet 执行异常", e);
        }
    }

}
