package com.qc.inspect.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * 方法级 @PreAuthorize 抛出的越权异常走不到 Security 的 accessDeniedHandler：
     * 它是在 DispatcherServlet 里抛的，会先被本类的兜底 Exception 处理器接住，
     * 结果越权变成「HTTP 200 + code 500」，前端只能看到一句「系统异常」。
     * 这里显式转成 403，和过滤器层的拒绝保持同一个语义。
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleDenied(AccessDeniedException e) {
        log.warn("越权访问: {}", e.getMessage());
        return R.fail(403, "无权访问该功能，请联系管理员确认角色");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValid(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return R.fail(400, msg);
    }

    /**
     * 上传超限。
     *
     * <p>不接的话会落到最下面的兜底，变成「系统异常: Maximum upload size exceeded」——
     * 现场看到一串英文只会以为系统坏了，看不出是自己传的图太大。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传超限: {}", e.getMessage());
        return R.fail(400, "文件太大了，单张控制在 10MB 以内。手机直接拍一般只有几 MB");
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("系统异常", e);
        return R.fail("系统异常: " + e.getMessage());
    }
}
