package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.model.vo.ConfigAccountVO;
import com.example.demo.model.vo.WebsiteVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConfigAccountService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 获取网站列表
     * @param username
     * @return
     */
    public List<ConfigAccountVO> getAccount(String username, String websiteId) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();  // 如果 Redis 中没有数据，返回一个空列表
        }

        // 将 List 中的 JSON 字符串反序列化为 WebSiteVO 列表
        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, ConfigAccountVO.class))
                .collect(Collectors.toList());
    }

    /**
     * 新增或修改网站
     * @param username 用户名
     * @param configAccountVO 网站信息
     */
    public void saveAccount(String username, String websiteId, ConfigAccountVO configAccountVO) {

        // 生成 Redis 中的 key
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);

        // 为新账号生成唯一 ID
        if (StringUtils.isBlank(configAccountVO.getId())) {
            configAccountVO.setId(IdUtil.getSnowflakeNextIdStr());  // 如果 ID 为空，生成新的 ID
        }

        // 获取 Redis 中的列表
        List<String> accountList = businessPlatformRedissonClient.getList(key);

        // 检查账号是否已经存在，若存在则进行更新
        boolean exists = accountList.stream()
                .anyMatch(json -> {
                    ConfigAccountVO site = JSONUtil.toBean(json, ConfigAccountVO.class);
                    return site.getId().equals(configAccountVO.getId());  // 根据 ID 判断是否已存在
                });

        if (exists) {
            // 如果账号已存在，替换旧数据
            accountList.replaceAll(json -> {
                ConfigAccountVO site = JSONUtil.toBean(json, ConfigAccountVO.class);
                if (site.getId().equals(configAccountVO.getId())) {
                    return JSONUtil.parse(configAccountVO).toString();
                }
                return json;
            });
        } else {
            // 如果账号不存在，直接新增
            configAccountVO.setWebsiteId(websiteId);
            accountList.add(JSONUtil.parse(configAccountVO).toString());
        }
    }

    /**
     * 删除网站
     * @param username 用户名
     * @param websiteId 要删除的网站ID
     */
    public void deleteAccount(String username, String websiteId, String accountId) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_ACCOUNT_PREFIX, username, websiteId);

        // 获取所有网站信息
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        // 找到对应的 website 并删除
        jsonList.stream()
                .filter(json -> JSONUtil.toBean(json, ConfigAccountVO.class).getId().equals(accountId))
                .findFirst()
                .ifPresent(json -> businessPlatformRedissonClient.getList(key).remove(json));
    }

}