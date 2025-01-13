package com.example.demo.core.factory;

import jakarta.annotation.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 通用的 API 调用流程模板
 */
public abstract class ApiTemplate {
    @Resource
    protected RestTemplate restTemplate;

    public Map<String, Object> execute(String url, Map<String, Object> params) {
        // 构建请求
        HttpEntity<String> request = buildRequest(params);

        // 调用接口
        ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

        // 解析响应
        return parseResponse(response.getBody());
    }

    protected abstract HttpEntity<String> buildRequest(Map<String, Object> params);
    protected abstract Map<String, Object> parseResponse(String responseBody);
}

