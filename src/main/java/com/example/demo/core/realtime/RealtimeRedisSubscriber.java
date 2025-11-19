package com.example.demo.core.realtime;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RealtimeRedisSubscriber implements InitializingBean, DisposableBean {

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;
    private final SimpMessagingTemplate messagingTemplate;

    private int listenerId = -1;

    public RealtimeRedisSubscriber(RedissonClient redissonClient, SimpMessagingTemplate messagingTemplate) {
        this.businessPlatformRedissonClient = redissonClient;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        RTopic topic = businessPlatformRedissonClient.getTopic("realtime:global");
        listenerId = topic.addListener(String.class, (channel, msg) -> {
            try {
                log.info("收到 Redis 消息 channel={}, msg={}", channel, msg);
                JSONObject json = JSONUtil.parseObj(msg);
                String username = json.getStr("username");
                if (StringUtils.isBlank(username)) {
                    log.warn("收到 Redis 推送但 username 为空, msg={}", msg);
                    return;
                }

                // 转发到 websocket topic，客户端订阅 /topic/realtime/{username}
                log.info("转发到 /topic/realtime/{} 内容: {}", username, json);
                messagingTemplate.convertAndSend("/topic/realtime/list/" + username, json.toString());
                messagingTemplate.convertAndSend("/topic/realtime/prompt/" + username, json.toString());
                log.info("转发完成 -> /topic/realtime/{}", username);
            } catch (Exception e) {
                log.info("转发 redis->ws 失败 msg={}", msg, e);
            }
        });
        log.info("Subscribed to realtime:global with listenerId={}", listenerId);
    }

    @Override
    public void destroy() throws Exception {
        if (listenerId >= 0) {
            businessPlatformRedissonClient.getTopic("realtime:global").removeListener(listenerId);
        }
    }
}
