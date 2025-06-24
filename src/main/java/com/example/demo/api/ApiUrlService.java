package com.example.demo.api;


import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import jakarta.annotation.Resource;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ApiUrlService {

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 从 Redis 获取 API 路径
      */
    public String getApiUrl(String siteId, String apiType) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_API_PREFIX);
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();
        if (json != null) {
            // 解析 JSON 数据
            JSONObject jsonObject = JSONUtil.parseObj(json);
            // 根据 API 类型获取 URL（login 或 info）
            JSONObject apiUrls = jsonObject.getJSONObject(apiType);
            return apiUrls.getStr(siteId); // 根据站点 ID 获取对应的 URL
        }
        return null;
    }

    /**
     * 赔率缓存处理
     * @param username      系统用户
     * @param id            赛事id
     * @param newOdds       当前赔率
     */
    public void updateOddsCache(String username, String id, double newOdds) {
        String oddsKey = KeyUtil.genKey(RedisConstants.PLATFORM_BET_ODDS_PREFIX, username, id);
        RBucket<Object> bucket = businessPlatformRedissonClient.getBucket(oddsKey);
        Object cached = bucket.get();
        long now = System.currentTimeMillis();

        JSONObject oddsJson = new JSONObject();
        oddsJson.putOpt("odds", newOdds);
        oddsJson.putOpt("time", now);

        if (cached == null) {
            bucket.set(oddsJson, Duration.ofHours(2));
        } else {
            JSONObject existing = JSONUtil.parseObj(cached);
            double existingOdds = existing.getDouble("odds");
            if (Math.abs(existingOdds - newOdds) >= 0.00001) {
                bucket.set(oddsJson, Duration.ofHours(2));
            }
        }
    }

}
