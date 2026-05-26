package com.example.demo.task;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class SnifferMatchSyncTask {

    private static final String SNIFFER_HOST = "localhost";
    private static final int SNIFFER_PORT = 3002;
    private static final String SNIFFER_MATCHES_URL = "http://" + SNIFFER_HOST + ":" + SNIFFER_PORT + "/api/matches";

    private static final String REDIS_KEY_ALL_MATCHES = RedisConstants.PLATFORM_599_MATCHES_PREFIX + ":matches";
    private static final String REDIS_KEY_SYNC_TIME = RedisConstants.PLATFORM_599_MATCHES_PREFIX + ":sync_time";

    private final OkHttpClient snifferClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.SECONDS)
            .build();

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 定时同步599-sniffer全量赛事列表到Redis
     * 每8小时执行一次
     */
    @Scheduled(fixedRate = 8 * 60 * 60 * 1000)
    public void syncMatchesToRedis() {
        log.info("开始同步599-sniffer全量赛事列表...");
        try {
            String jsonResponse = fetchAllMatches();
            if (jsonResponse != null && !jsonResponse.isEmpty()) {
                // 解析JSON结构：{"success": true, "count": 1251, "data": [...]}
                JSONObject responseObj = JSONUtil.parseObj(jsonResponse);
                Boolean success = responseObj.getBool("success");
                JSONArray dataArray = responseObj.getJSONArray("data");
                
                if (success != null && success && dataArray != null && !dataArray.isEmpty()) {
                    int count = responseObj.getInt("count", 0);
                    businessPlatformRedissonClient.getBucket(REDIS_KEY_ALL_MATCHES).set(dataArray.toString());
                    businessPlatformRedissonClient.getBucket(REDIS_KEY_SYNC_TIME).set(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                    log.info("599-sniffer全量赛事列表同步成功，共 {} 场赛事", count);
                } else {
                    log.warn("599-sniffer返回数据无效: success={}, dataSize={}", success, 
                            dataArray != null ? dataArray.size() : 0);
                }
            }
        } catch (Exception e) {
            log.error("同步599-sniffer全量赛事列表失败", e);
        }
    }
    /**
     * 从599-sniffer获取全量赛事列表
     */
    private String fetchAllMatches() throws IOException {
        Request request = new Request.Builder().url(SNIFFER_MATCHES_URL).get().build();
        try (Response response = snifferClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("获取全量赛事列表失败，状态码={}", response.code());
                return null;
            }
            return response.body().string();
        }
    }

    /**
     * 手动触发同步（用于测试）
     */
    public void triggerSync() {
        syncMatchesToRedis();
    }
}
