package com.sky.exception;

/**
 * 用户名重复异常
 */
public class UsernameDuplicateException extends BaseException {

    public UsernameDuplicateException() {
    }

    public UsernameDuplicateException(String msg) {
        super(msg);
    }

}
