package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.dto.settings.*;
import com.example.demo.model.vo.settings.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class SettingsFilterService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 获取过滤相关-赔率范围
     * @param username
     * @return
     */
    public List<OddsRangeDTO> getOddsRanges(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_ODDSRANGE_PREFIX, username);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();  // 如果 Redis 中没有数据，返回一个空列表
        }
        // 将 List 中的 JSON 字符串反序列化为 ContrastDTO 列表
        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, OddsRangeDTO.class))
                .toList();
    }

    /**
     * 过滤相关-赔率范围 新增
     * @param username
     * @return
     */
    public void saveOddsRanges(String username, OddsRangeVO oddsRangeVO) {
        if (oddsRangeVO.getOddsGreater() > oddsRangeVO.getOddsLess()) {
            throw new BusinessException(SystemError.ODDSRANGE_1301);
        }
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_ODDSRANGE_PREFIX, username);
        // 获取 Redis 中的列表
        List<String> contrastList = businessPlatformRedissonClient.getList(key);
        // 检查网站是否已经存在，若存在则进行更新
        boolean exists = contrastList.stream()
                .anyMatch(json -> {
                    OddsRangeVO oddsRange = JSONUtil.toBean(json, OddsRangeVO.class);
                    return oddsRange.getWebsiteId().equals(oddsRangeVO.getWebsiteId());  // 根据 ID 判断是否已存在
                });
        if (exists) {
            throw new BusinessException(SystemError.ODDSRANGE_1300);
        }
        contrastList.add(JSONUtil.parse(oddsRangeVO).toString());
    }

    /**
     * 过滤相关-赔率范围 - 删除
     * @param username 用户名
     * @param id 要删除的网站ID
     */
    public void deleteOddsRanges(String username, String id) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_ODDSRANGE_PREFIX, username);

        // 获取所有网站信息
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        // 找到对应的 website 并删除
        jsonList.stream()
                .filter(json -> JSONUtil.toBean(json, OddsRangeVO.class).getWebsiteId().equals(id))
                .findFirst()
                .ifPresent(json -> businessPlatformRedissonClient.getList(key).remove(json));
    }

    /**
     * 获取过滤相关-时间范围
     * @param username
     * @return
     */
    public List<TimeFrameDTO> getTimeFrames(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_TIMEFRAME_PREFIX, username);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();  // 如果 Redis 中没有数据，返回一个空列表
        }
        // 将 List 中的 JSON 字符串反序列化为 ContrastDTO 列表
        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, TimeFrameDTO.class))
                .toList();
    }

    /**
     * 过滤相关-时间范围 新增
     * @param username
     * @return
     */
    public void saveTimeFrames(String username, TimeFrameVO timeFrameVO) {
        if (timeFrameVO.getTimeFormSec() > timeFrameVO.getTimeToSec()) {
            throw new BusinessException(SystemError.TIMEFRAME_1311);
        }
        List<TimeFrameDTO> timeFrames = getTimeFrames(username);

        Optional<TimeFrameDTO> timeFrame = timeFrames.stream()
                .filter(w -> Objects.equals(w.getBallType(), timeFrameVO.getBallType()) && Objects.equals(w.getCourseType(), timeFrameVO.getCourseType()))
                .findFirst();
        if (timeFrame.isPresent()) {
            throw new BusinessException(SystemError.TIMEFRAME_1312);
        }
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_TIMEFRAME_PREFIX, username);
        // 获取 Redis 中的列表
        List<String> contrastList = businessPlatformRedissonClient.getList(key);

        if (StringUtils.isBlank(timeFrameVO.getId())) {
            timeFrameVO.setId(IdUtil.getSnowflakeNextIdStr());  // 如果 ID 为空，生成新的 ID
        }

        contrastList.add(JSONUtil.parse(timeFrameVO).toString());
    }

    /**
     * 过滤相关-赔率范围 - 删除
     * @param username 用户名
     * @param id 要删除的网站ID
     */
    public void deleteTimeFrames(String username, String id) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_FILTER_TIMEFRAME_PREFIX, username);

        // 获取所有网站信息
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        // 找到对应的 website 并删除
        jsonList.stream()
                .filter(json -> JSONUtil.toBean(json, TimeFrameVO.class).getId().equals(id))
                .findFirst()
                .ifPresent(json -> businessPlatformRedissonClient.getList(key).remove(json));
    }

}
