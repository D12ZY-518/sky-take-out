package com.sky.exception;

/**
 * 订单越权访问（内部接口归属校验：订单存在但不属于当前用户）
 *
 * 【学习要点·为什么"不存在"和"不属于"分两个异常，以及代价】
 * 404（OrderNotFoundException）与 403（本类）语义不同：
 * Python 工具按状态码生成不同话术 —— 404 引导核对单号，403 明确告知无权。
 * 顺序上必须先判存在再判归属（见 InternalApiServiceImpl.getOrderDetail）：
 * 第一原因是 null 安全 —— 订单都查不到就没有 userId 可比，先比归属会 NPE。
 * 【诚实的风险提示】区分 404/403 是有代价的：调用方可借此探测"某订单号是否存在"
 * （枚举预言机）。本项目能接受，因为 /internal/** 只对持密钥的 Python 服务开放；
 * 若同一接口直接暴露给不可信调用方，正确做法是两类错误统一话术（模糊化）。
 */
public class OrderAccessDeniedException extends BaseException {

    public OrderAccessDeniedException(String msg) {
        super(msg);
    }
}
