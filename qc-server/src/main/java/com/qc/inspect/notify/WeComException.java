package com.qc.inspect.notify;

/**
 * 企业微信接口失败。
 *
 * <p>带 errcode 与 errmsg 原文：排障时「60020」这五个字符比任何中文概括都值钱，
 * 它直接对应「调用 IP 不在可信 IP 列表」，改一句配置就好；而「发送失败」
 * 只能让人从头翻日志。
 */
public class WeComException extends RuntimeException {

    private final int errCode;

    public WeComException(int errCode, String message) {
        super(message);
        this.errCode = errCode;
    }

    public WeComException(String message, Throwable cause) {
        super(message, cause);
        this.errCode = -1;
    }

    public int getErrCode() {
        return errCode;
    }
}
