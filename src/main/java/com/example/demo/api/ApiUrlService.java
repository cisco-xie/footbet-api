package com.example.demo.api;


import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

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
}
