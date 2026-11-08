package com.sky.mapper;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WorkspaceMapper {

    // ① 营业额：已完成(status=5)订单金额合计，窗口内
    Double getTurnover(LocalDateTime begin, LocalDateTime end);

    // ② 有效订单数：status=5，窗口内
    Integer getValidOrderCount(LocalDateTime begin, LocalDateTime end);

    // ③ 订单总数：窗口内（不分状态）
    Integer getOrderCount(LocalDateTime begin, LocalDateTime end);

    // ④ 新增用户：user 表 create_time 在窗口内
    Integer getNewUsers(LocalDateTime begin, LocalDateTime end);

    // ⑤ overviewOrders 的 5 个数字怎么拿？（思考点见下）
    Integer countOrderByStatus(Integer status);

    // ⑥⑦ 菜品/套餐 起售停售计数（两个表，方法名别混）
    Integer countDishByStatus(Integer status);
    Integer countSetmealByStatus(Integer status);
}