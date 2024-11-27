package com.example.demo.core.support;


import cn.hutool.core.codec.Base64;
import cn.hutool.core.text.UnicodeUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import com.example.demo.model.dto.AdminLoginDTO;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Objects;

/**
 * @description 公共controller基类
 * @author 谢诗宏
 * @date 2024年11月27日
 */
@Slf4j
public class BaseController {

    /**
     * 认证信息Http请求头cookie
     */
    public static final String JWT_TOKEN_COOKIE = "authorized-token";

    /**
     * 令牌前缀
     */
    public static final String JWT_TOKEN_PREFIX = "Bearer ";

    /**
     * 得到request对象
     */
    public HttpServletRequest getRequest() {
        return ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getRequest();
    }

    /**
     * 得到response对象
     */
    public HttpServletResponse getResponse() {
        return ((ServletRequestAttributes) Objects.requireNonNull(RequestContextHolder.getRequestAttributes())).getResponse();
    }

    /**
     * 获取当前用户token
     * @return
     */
    public AdminLoginDTO getUser() {
        Cookie[] cookies = getRequest().getCookies();
        for (int i = 0; i < cookies.length; i++) {
            if (StringUtils.equals(cookies[i].getName(), JWT_TOKEN_COOKIE)) {
                JSONObject tokenJson = JSONUtil.parseObj(URLUtil.decode(cookies[i].getValue()));
                final JWT jwt = JWTUtil.parseToken(tokenJson.getStr("accessToken"));
                return JSONUtil.toBean(jwt.getPayload().getClaimsJson(), AdminLoginDTO.class);
            }
        }
        return null;
    }
}
