package com.sky.service;

import java.time.LocalDateTime;

import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;

public interface WorkspaceService {

    /**
     * 查询指定时间段的运营数据（今日概览）
     */
    BusinessDataVO getBusinessData(LocalDateTime begin, LocalDateTime end);

    /**
     * 订单管理总览（全量，不限时间）
     */
    OrderOverViewVO getOverviewOrders();

    /**
     * 菜品总览（起售/停售数量）
     */
    DishOverViewVO getOverviewDishes();

    /**
     * 套餐总览（起售/停售数量）
     */
    SetmealOverViewVO getOverviewSetmeals();
}