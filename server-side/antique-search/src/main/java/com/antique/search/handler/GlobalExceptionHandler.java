package com.antique.search.handler;

import com.antique.constant.MessageConstant;
import com.antique.exception.BaseException;
import com.antique.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 — 搜索服务
 *
 * <h3>为什么要再写一份（不能复用主服务的）</h3>
 * <p>{@code @RestControllerAdvice} 只对<b>当前 Spring 容器</b>生效。
 * 搜索服务是独立进程，主服务那个处理器扫描不到这里，所以必须自己有一份 ——
 * 否则搜索服务报错时返回的是 Spring 默认的错误 JSON
 * （{@code {"timestamp":...,"status":500,...}}），和主服务的 {@code Result} 格式不一致，
 * 前端要写两套解析逻辑。
 *
 * <h3>处理顺序（由具体到笼统）</h3>
 * <ol>
 *   <li>{@code BaseException} → 业务异常，返回自定义消息</li>
 *   <li>{@code BindException} → 参数绑定/校验失败</li>
 *   <li>{@code HttpMessageNotReadableException} → 请求体格式错误</li>
 *   <li>{@code Exception} → 兜底，返回"系统异常"，记录完整堆栈</li>
 * </ol>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常 — 直接返回异常中的消息给前端
     */
    @ExceptionHandler(BaseException.class)
    public Result<?> handleBaseException(BaseException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 参数绑定/校验异常
     */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException ex) {
        String msg = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(e -> e.getDefaultMessage())
                .findFirst()
                .orElse(MessageConstant.PARAM_ERROR);
        return Result.error(msg);
    }

    /**
     * 请求体解析异常 — JSON 格式错误或字段类型不匹配
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return Result.error(MessageConstant.PARAM_FORMAT_ERROR);
    }

    /**
     * 兜底异常 — 记录完整堆栈，向前端返回通用错误消息
     */
    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception ex) {
        log.error("系统异常", ex);
        return Result.error(MessageConstant.SYSTEM_ERROR);
    }
}
