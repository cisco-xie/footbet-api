package com.example.demo.api;

import cn.hutool.json.JSONUtil;
import com.example.demo.common.constants.RedisConstants;
import com.example.demo.common.utils.KeyUtil;
import com.example.demo.model.dto.settings.DetectionDTO;
import com.example.demo.model.vo.settings.DetectionVO;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Component
public class SettingsAbnormalService {

    @Resource(name = "businessPlatformRedissonClient")
    private RedissonClient businessPlatformRedissonClient;

    public DetectionDTO getDetection(String username) {
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_ABNORMAL_DETECTION_PREFIX, username);
        String json = (String) businessPlatformRedissonClient.getBucket(key).get();
        if (StringUtils.isBlank(json)) {
            DetectionDTO dto = new DetectionDTO();
            dto.setEnabled(0);
            dto.setInterval(30);
            return dto;
        }
        DetectionDTO dto = JSONUtil.toBean(json, DetectionDTO.class);
        if (dto.getEnabled() == null) {
            dto.setEnabled(0);
        }
        if (dto.getInterval() == null || dto.getInterval() <= 0) {
            dto.setInterval(30);
        }
        return dto;
    }

    public void saveDetection(String username, DetectionVO detectionVO) {
        if (detectionVO.getEnabled() == null) {
            detectionVO.setEnabled(0);
        }
        if (detectionVO.getInterval() == null || detectionVO.getInterval() <= 0) {
            detectionVO.setInterval(30);
        }
        String key = KeyUtil.genKey(RedisConstants.PLATFORM_SETTINGS_ABNORMAL_DETECTION_PREFIX, username);
        businessPlatformRedissonClient.getBucket(key).set(JSONUtil.toJsonStr(detectionVO));
    }
}
