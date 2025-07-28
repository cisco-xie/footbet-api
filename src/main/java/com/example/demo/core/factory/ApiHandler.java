package com.example.demo.core.factory;

import cn.hutool.json.JSONObject;
import com.example.demo.config.OkHttpProxyDispatcher;
import com.example.demo.model.vo.ConfigAccountVO;

import java.util.Map;

/**
 * API 处理器接口
 */
public interface ApiHandler {
    Map<String, String> buildHeaders(JSONObject params);    // 构建请求头
    String buildRequest(JSONObject params);                 // 构建请求体
    Map<String, Object> parseResponse(JSONObject params, OkHttpProxyDispatcher.HttpResult result);  // 解析响应,带请求参数版
    JSONObject execute(ConfigAccountVO userConfig, JSONObject params);  // 发送请求
}
