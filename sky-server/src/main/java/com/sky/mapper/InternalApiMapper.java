package com.sky.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.CustomerServiceTicket;
import com.sky.entity.Orders;

/**
 * 内部接口专用 Mapper（供 Python Agent 回调 /internal/** 使用）
 * 只暴露"售后客服"场景需要的最小查询面，不复用管理端/用户端的复杂查询。
 */
@Mapper
public interface InternalApiMapper {

    /**
     * 查询某用户的订单列表（按下单时间倒序），可按状态过滤、限量
     */
    @Select("<script>"
            + "select * from orders where user_id = #{userId} "
            + "<if test='status != null'> and status = #{status} </if>"
            + "order by order_time desc limit #{limit}"
            + "</script>")
    List<Orders> listOrders(@Param("userId") Long userId,
                            @Param("status") Integer status,
                            @Param("limit") int limit);

    /**
     * 按订单号查订单（不带归属条件；归属由调用方对比 userId 判定，才能区分 404/403）
     */
    @Select("select * from orders where number = #{orderNumber}")
    Orders getByNumber(String orderNumber);

    /**
     * 新建售后工单
     */
    @Insert("insert into customer_service_ticket "
            + "(ticket_no, user_id, request_id, order_number, category, content, status, create_time) "
            + "values (#{ticketNo}, #{userId}, #{requestId}, #{orderNumber}, #{category}, #{content}, #{status}, #{createTime})")
    void insertTicket(CustomerServiceTicket ticket);

    @Select("select * from customer_service_ticket where user_id = #{userId} and request_id = #{requestId}")
    CustomerServiceTicket getTicketByRequestId(@Param("userId") Long userId,
                                               @Param("requestId") String requestId);
}
