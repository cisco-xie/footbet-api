package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.model.vo.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class WebsiteService {

    @Lazy
    @Resource
    private HandicapApi handicapApi;

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 获取指定网站
     * @param username
     * @return
     */
    public WebsiteVO getWebsite(String username, String websiteId) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, username);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return null;  // 如果 Redis 中没有数据，返回一个空列表
        }

        // 将 List 中的 JSON 字符串反序列化为 WebSiteVO 列表
        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, WebsiteVO.class))
                .filter(websiteVO -> websiteVO.getId().equals(websiteId))
                .findFirst() // 找到第一个匹配的对象
                .orElse(null); // 如果没有匹配对象，返回 null
    }

    /**
     * 获取网站使用的地址
     * @param username
     * @return
     */
    public String getWebsiteBaseUrl(String username, String websiteId) {
        WebsiteVO website = getWebsite(username, websiteId);
        return website.getBaseUrls().get(0);
    }

    /**
     * 获取网站列表
     * @param username
     * @return
     */
    public List<WebsiteVO> getWebsites(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, username);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();  // 如果 Redis 中没有数据，返回一个空列表
        }

        // 将 List 中的 JSON 字符串反序列化为 WebSiteVO 列表
        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, WebsiteVO.class))
                .collect(Collectors.toList());
    }

    /**
     * 新增或修改网站
     * @param username 用户名
     * @param websiteVO 网站信息
     */
    public void saveWebsite(String username, WebsiteVO websiteVO) {

        // 生成 Redis 中的 key
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, username);

        // 为新网站生成唯一 ID
        if (StringUtils.isBlank(websiteVO.getId())) {
            websiteVO.setId(IdUtil.getSnowflakeNextIdStr());  // 如果 ID 为空，生成新的 ID
        }

        // 获取 Redis 中的列表
        List<String> websiteList = businessPlatformRedissonClient.getList(key);

        // 检查网站是否已经存在，若存在则进行更新
        boolean exists = websiteList.stream()
                .anyMatch(json -> {
                    WebsiteVO site = JSONUtil.toBean(json, WebsiteVO.class);
                    return site.getId().equals(websiteVO.getId());  // 根据 ID 判断是否已存在
                });

        if (exists) {
            // 如果网站已存在，替换旧数据
            websiteList.replaceAll(json -> {
                WebsiteVO site = JSONUtil.toBean(json, WebsiteVO.class);
                if (site.getId().equals(websiteVO.getId())) {
                    if (!Objects.equals(site.getOddsType(), websiteVO.getOddsType())) {
                        // 如果修改了赔率类型，则执行盘口偏好设置
                        handicapApi.preferences(username, site.getId(), null);
                    }
                    return JSONUtil.parse(websiteVO).toString();
                }
                return json;
            });
        } else {
            // 如果网站不存在，直接新增
            websiteList.add(JSONUtil.parse(websiteVO).toString());
        }
    }

    /**
     * 删除网站
     * @param username 用户名
     * @param websiteId 要删除的网站ID
     */
    public void deleteWebsite(String username, String websiteId) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_WEBSITE_ALL_PREFIX, username);

        // 获取所有网站信息
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        // 找到对应的 website 并删除
        jsonList.stream()
                .filter(json -> JSONUtil.toBean(json, WebsiteVO.class).getId().equals(websiteId))
                .findFirst()
                .ifPresent(json -> businessPlatformRedissonClient.getList(key).remove(json));
    }

}
