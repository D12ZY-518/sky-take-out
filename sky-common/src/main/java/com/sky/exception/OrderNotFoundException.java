package com.sky.exception;

/**
 * 订单不存在（内部接口归属校验：按订单号查不到单）
 *
 * 【学习要点·异常怎么变成 404】
 * 继承链 OrderNotFoundException → BaseException → RuntimeException：
 * Service 抛出 → InternalApiController 手动 catch → 映射 HTTP 404。
 * （不进全局异常处理器，因为内部接口要给 Python 精确的 404/403 状态码。）
 * 命名风格：XXXException 表达"哪类业务出了什么事"，调用方按类型分支处理，
 * 与 Java 里 catch (IOException e) 按类型分流是同一套思想。
 */
public class OrderNotFoundException extends BaseException {

    public OrderNotFoundException(String msg) {
        super(msg);
    }
}
