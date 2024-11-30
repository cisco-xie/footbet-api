package com.example.demo.task;


import com.example.demo.api.ConfigService;
import com.example.demo.api.FalaliApi;
import com.example.demo.model.vo.ConfigPlanVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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

    @Scheduled(cron = "0/5 * * * * ?")
    public void bet() {
        // todo 第一步先获取所有投注方案
        falaliApi.autoBet();
        // todo 第二步根据方案进行查找对应期数

        // todo 第三步进行校验当前期数是否已投注成功

        // todo 第四步进行投注
    }
}
