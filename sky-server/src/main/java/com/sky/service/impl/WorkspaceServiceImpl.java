package com.sky.service.impl;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sky.mapper.WorkspaceMapper;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {

    @Autowired
    private WorkspaceMapper workspaceMapper;

    @Override
    public BusinessDataVO getBusinessData(LocalDateTime begin, LocalDateTime end) {
        // 1. turnover = workspaceMapper.getTurnover(begin, end); 判 null → 0.0
        Double turnover = workspaceMapper.getTurnover(begin, end);
        turnover = turnover == null ? 0.0 : turnover;

        // 2. validOrderCount = workspaceMapper.getValidOrderCount(begin, end); 判 null → 0
        Integer validOrderCount = workspaceMapper.getValidOrderCount(begin, end);
        
        // 3. orderCount = workspaceMapper.getOrderCount(begin, end); 判 null → 0
        Integer orderCount = workspaceMapper.getOrderCount(begin, end);
        orderCount = orderCount == null ? 0 : orderCount;
        
        // 4. 完成率 = orderCount == 0 ? 0.0 : validOrderCount*1.0 / orderCount
        Double completionRate = orderCount == 0 ? 0.0 : validOrderCount*1.0 / orderCount;
        
        // 5. 客单价 = validOrderCount == 0 ? 0.0 : turnover / validOrderCount
        Double avgOrderPrice = validOrderCount == 0 ? 0.0 : turnover / validOrderCount;
        
        // 6. newUsers = workspaceMapper.getNewUsers(begin, end); 判 null → 0
        Integer newUsers = workspaceMapper.getNewUsers(begin, end);
        newUsers = newUsers == null ? 0 : newUsers;
        
        // 7. builder() 组装返回
        return BusinessDataVO.builder()
                .turnover(turnover)
                .validOrderCount(validOrderCount)
                .newUsers(newUsers)
                .orderCompletionRate(completionRate)
                .unitPrice(avgOrderPrice)
                .build();   
    }

    @Override
    public OrderOverViewVO getOverviewOrders() {
        // waitingOrders = status 2（待接单）
        Integer waitingOrders = workspaceMapper.countOrderByStatus(2);
        
        // deliveredOrders = status 3（待派送）
        Integer deliveredOrders = workspaceMapper.countOrderByStatus(3);
        
        // completedOrders = status 5（已完成）
        Integer completedOrders = workspaceMapper.countOrderByStatus(5);
        
        // cancelledOrders = status 6（已取消）
        Integer cancelledOrders = workspaceMapper.countOrderByStatus(6);
       
        // allOrders = 总数
        Integer allOrders = workspaceMapper.countOrderByStatus(null) == null ? 0 : workspaceMapper.countOrderByStatus(null);
        return OrderOverViewVO.builder()
                .waitingOrders(waitingOrders)
                .deliveredOrders(deliveredOrders)
                .completedOrders(completedOrders)
                .cancelledOrders(cancelledOrders)
                .allOrders(allOrders)
                .build();
    }

    @Override
    public DishOverViewVO getOverviewDishes() {
        // sold = status 1；discontinued = status 0
        Integer sold = workspaceMapper.countDishByStatus(1) == null ? 0 : workspaceMapper.countDishByStatus(1);
        Integer discontinued = workspaceMapper.countDishByStatus(0) == null ? 0 : workspaceMapper.countDishByStatus(0);
        return DishOverViewVO.builder()
                .sold(sold)
                .discontinued(discontinued)
                .build();
    }

    @Override
    public SetmealOverViewVO getOverviewSetmeals() {
        // sold = status 1；discontinued = status 0
        Integer sold = workspaceMapper.countSetmealByStatus(1) == null ? 0 : workspaceMapper.countSetmealByStatus(1);
        Integer discontinued = workspaceMapper.countSetmealByStatus(0) == null ? 0 : workspaceMapper.countSetmealByStatus(0);
        return SetmealOverViewVO.builder()
                .sold(sold)
                .discontinued(discontinued)
                .build();
    }
}