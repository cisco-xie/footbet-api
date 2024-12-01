package com.example.demo.common.enmu;


import com.example.demo.common.constants.ErrCode;

/**
 * @author 谢诗宏
 * @description 统一异常码
 * @date 2024年11月19日
 */
public enum SystemError implements ErrCode {

    /**
     * 基础异常
     */
    SYS_500(500, "服务器繁忙,请稍后重试！"),
    SYS_502(502, "系统维护中"),
    SYS_503(503, "未找到[%s]微服务,请检查服务是否可用"),
    SYS_504(504, "未找到微服务"),

    SYS_400(400, "错误的请求"),
    SYS_401(401, "没有权限"),
    SYS_402(402, "参数不完整"),
    SYS_404(404, "Not Found"),

    SYS_409(409, "缺少请求参数[%s]"),
    SYS_410(410, "请求方式错误"),
    SYS_411(411, "当前数据不存在"),
    SYS_418(418, "请求参数[%s]格式错误"),

    /**
     * 用户相关异常--1000起
     */
    USER_1000(1000, "未登录"),
    USER_1001(1001, "token过期"),
    USER_1002(1002, "请求异常，请检查本地网络或代理配置"),
    USER_1003(1003, "账号[%s]已存在，无法新增"),
    USER_1004(1004, "账号[%s]不存在，无法修改"),
    USER_1005(1005, "账号方案删除失败"),
    USER_1006(1006, "账号登录失败"),
    USER_1007(1007, "账号配置异常"),
    USER_1008(1008, "登录账号或密码错误"),

    /**
     * 下单相关异常--1100起
     */
    ORDER_1100(1100, "下注失败，请去[异常投注]页面查看详情"),

    ;

    private final Integer code;
    private final String msg;

    SystemError(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public Integer getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }

    public static SystemError getDefined(Integer code) {
        for (SystemError err : SystemError.values()) {
            if (err.code.equals(code)) {
                return err;
            }
        }
        return SystemError.SYS_500;
    }

}