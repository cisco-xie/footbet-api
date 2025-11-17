package com.example.demo.api;

import cn.hutool.json.JSONObject;
import com.example.demo.common.utils.KeyUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class RealtimeIndexService {

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 将新 bet 写入 bucket，添加到用户索引并发布全局通知（轻量）
     *
     * @param username  用户名（用于路由）
     * @param betKey    存储 bet 的 bucket key（唯一，如 "bet:{uuid}"）
     * @param betJson   bet 的 JSON 字符串（完整内容写入 bucket）
     */
    public void pushRealtimeIndex(String username, String betKey, String betJson) {
        try {
            // 写入 bucket（完整数据）
            RBucket<String> bucket = businessPlatformRedissonClient.getBucket(betKey);
            bucket.set(betJson);

            // 写入索引列表（尾部）
            String indexKey = KeyUtil.genKey("INDEX", username, "realtime");
            RList<String> index = businessPlatformRedissonClient.getList(indexKey);
            index.add(betKey);

            // 发布轻量通知到 global channel（避免把完整 json 发到 pubsub）
            JSONObject msg = new JSONObject();
            msg.set("type", "newBet");
            msg.set("username", username);
            msg.set("key", betKey);
            // 可选：发送 index 长度，便于前端快速定位增量
            msg.set("index", index.size() - 1);

            RTopic topic = businessPlatformRedissonClient.getTopic("realtime:global");
            topic.publish(msg.toString());
            log.info("pushRealtimeIndex 成功 username={}, key={}, index={}", username, betKey, index.size()-1);
        } catch (Exception e) {
            log.error("pushRealtimeIndex 失败 username={} key={}", username, betKey, e);
        }
    }
}

