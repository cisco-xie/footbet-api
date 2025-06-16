package com.example.demo.api;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.enmu.SystemError;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.core.exception.BusinessException;
import com.example.demo.model.dto.settings.BetAmountDTO;
import com.example.demo.model.dto.settings.ContrastDTO;
import com.example.demo.model.dto.settings.OddsScanDTO;
import com.example.demo.model.dto.settings.ProfitDTO;
import com.example.demo.model.vo.WebsiteVO;
import com.example.demo.model.vo.settings.BetAmountVO;
import com.example.demo.model.vo.settings.ContrastVO;
import com.example.demo.model.vo.settings.OddsScanVO;
import com.example.demo.model.vo.settings.ProfitVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class SettingsService {

    @Resource(name = "defaultRedissonClient")
    private RedissonClient redisson;

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    /**
     * 获取常规设置-对比分析
     * @param username
     * @return
     */
    public List<ContrastDTO> getContrasts(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_CONTRAST_PREFIX, username);

        // 从 Redis 中获取 List 数据
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        if (jsonList == null || jsonList.isEmpty()) {
            return Collections.emptyList();  // 如果 Redis 中没有数据，返回一个空列表
        }
        // 将 List 中的 JSON 字符串反序列化为 ContrastDTO 列表
        return jsonList.stream()
                .map(json -> JSONUtil.toBean(json, ContrastDTO.class))
                .toList();
    }

    /**
     * 新增或修改常规设置-对比分析
     * @param username 用户名
     * @param contrastVO 对比网站信息
     */
    public void saveContrast(String username, ContrastVO contrastVO) {

        if (StringUtils.equals(contrastVO.getWebsiteIdA(), contrastVO.getWebsiteIdB())) {
            throw new BusinessException(SystemError.CONTRAST_1200);
        }

        // 生成 Redis 中的 key
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_CONTRAST_PREFIX, username);

        // 为新网站生成唯一 ID
        if (StringUtils.isBlank(contrastVO.getId())) {
            contrastVO.setId(IdUtil.getSnowflakeNextIdStr());  // 如果 ID 为空，生成新的 ID
        }

        // 获取 Redis 中的列表
        List<String> contrastList = businessPlatformRedissonClient.getList(key);

        // 检查是否存在相同的网站对（无论顺序）
        boolean duplicateExists = contrastList.stream()
                .anyMatch(json -> {
                    ContrastVO site = JSONUtil.toBean(json, ContrastVO.class);
                    return (StringUtils.equals(site.getWebsiteIdA(), contrastVO.getWebsiteIdA())
                            && StringUtils.equals(site.getWebsiteIdB(), contrastVO.getWebsiteIdB()))
                            || (StringUtils.equals(site.getWebsiteIdA(), contrastVO.getWebsiteIdB())
                            && StringUtils.equals(site.getWebsiteIdB(), contrastVO.getWebsiteIdA()));
                });

        if (duplicateExists) {
            throw new BusinessException(SystemError.CONTRAST_1201); // 自定义错误，表示对比组合已存在
        }

        // 检查网站是否已经存在，若存在则进行更新
        boolean exists = contrastList.stream()
                .anyMatch(json -> {
                    ContrastVO site = JSONUtil.toBean(json, ContrastVO.class);
                    return site.getId().equals(contrastVO.getId());  // 根据 ID 判断是否已存在
                });

        if (exists) {
            // 如果网站已存在，替换旧数据
            contrastList.replaceAll(json -> {
                ContrastVO site = JSONUtil.toBean(json, ContrastVO.class);
                if (site.getId().equals(contrastVO.getId())) {
                    return JSONUtil.parse(contrastVO).toString();
                }
                return json;
            });
        } else {
            // 如果网站不存在，直接新增
            contrastList.add(JSONUtil.parse(contrastVO).toString());
        }
    }

    /**
     * 常规设置-对比分析 - 删除
     * @param username 用户名
     * @param id 要删除的网站ID
     */
    public void deleteContrast(String username, String id) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_CONTRAST_PREFIX, username);

        // 获取所有网站信息
        List<String> jsonList = businessPlatformRedissonClient.getList(key);

        // 找到对应的 website 并删除
        jsonList.stream()
                .filter(json -> JSONUtil.toBean(json, WebsiteVO.class).getId().equals(id))
                .findFirst()
                .ifPresent(json -> businessPlatformRedissonClient.getList(key).remove(json));
    }

    /**
     * 获取常规设置-赔率扫描
     * @param username
     * @return
     */
    public OddsScanDTO getOddsScan(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_ODDSSCAN_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, OddsScanDTO.class);
    }

    /**
     * 修改常规设置-赔率扫描
     * @param username
     * @return
     */
    public void saveOddsScan(String username, OddsScanVO oddsScanVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_ODDSSCAN_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(oddsScanVO));
    }

    /**
     * 获取常规设置-利润设置
     * @param username
     * @return
     */
    public ProfitDTO getProfit(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_PROFIT_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, ProfitDTO.class);
    }

    /**
     * 修改常规设置-利润设置
     * @param username
     * @return
     */
    public void saveProfit(String username, ProfitVO profitVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_PROFIT_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(profitVO));
    }

    /**
     * 获取常规设置-投注金额
     * @param username
     * @return
     */
    public BetAmountDTO getBetAmount(String username) {

        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_AMOUNT_PREFIX, username);

        // 从 Redis 中获取 List 数据
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();

        if (StringUtils.isBlank(json)) {
            return null;
        }
        return JSONUtil.toBean(json, BetAmountDTO.class);
    }

    /**
     * 修改常规设置-投注金额
     * @param username
     * @return
     */
    public void saveBetAmount(String username, BetAmountVO betAmountVO) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_GENERAL_AMOUNT_PREFIX, username);
        // 从 Redis 中获取数据
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(betAmountVO));
    }
}
