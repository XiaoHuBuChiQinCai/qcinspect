package com.qc.inspect.common;

/**
 * 业务异常，由全局异常处理器统一转成 R.fail。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }

    public static BizException of(String message) {
        return new BizException(message);
    }
}
