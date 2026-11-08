package com.sky.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.OrderDetail;

@Mapper
public interface OrderDetailMapper {
    
    void insertBatch(List<OrderDetail> orderDetails);

    /**
     * 根据订单ID查询订单详情
     * @param orderId
     * @return
     */
    @Select("select * from order_detail where order_id = #{orderId}")
    List<OrderDetail> getByOrderId(Long orderId);
}
