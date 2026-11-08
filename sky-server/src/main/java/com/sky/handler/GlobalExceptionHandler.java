package com.sky.handler;

import com.sky.exception.BaseException;
import com.sky.exception.UserNotLoginException;
import com.sky.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，处理项目中抛出的业务异常
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 登录态异常必须使用 HTTP 401，不能被普通业务异常折成 HTTP 200。
     */
    @ExceptionHandler(UserNotLoginException.class)
    public ResponseEntity<Result<String>> exceptionHandler(UserNotLoginException ex) {
        log.warn("用户未登录或登录态无效：{}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.error(ex.getMessage()));
    }

    /**
     * 捕获业务异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result<String> exceptionHandler(BaseException ex){
        log.error("异常信息：{}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 捕获数据库重复键异常
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result<String> exceptionHandler(DuplicateKeyException ex){
        log.error("数据库重复键异常：{}", ex.getMessage());
        return Result.error("数据已存在，请检查用户名是否重复");
    }

    /**
     * 兜底：捕获所有未预期的异常，避免直接 500
     * @param ex
     * @return
     */
    @ExceptionHandler
    public Result<String> exceptionHandler(Exception ex){
        log.error("未知异常：", ex);
        return Result.error(ex.getMessage());
    }

}
