package com.sky.controller.admin;

import io.swagger.annotations.Api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.result.Result;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.DishOverViewVO;
import com.sky.vo.OrderOverViewVO;
import com.sky.vo.SetmealOverViewVO;

@RestController("adminWorkspaceController")   // 命名防冲突
@RequestMapping("/admin/workspace")
@Api(tags = "管理端-工作台相关接口")
public class WorkspaceController {

    @Autowired
    private WorkspaceService workspaceService;

    /**
     * 查询今日运营数据（begin=今天0点，end=明天0点，半开区间）
     */
    @GetMapping("/businessData")
    public Result<BusinessDataVO> businessData() {
        LocalDateTime begin = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime end = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.MIN);
        return Result.success(workspaceService.getBusinessData(begin, end));
    }

    @GetMapping("/overviewOrders")
    public Result<OrderOverViewVO> overviewOrders() {
        return Result.success(workspaceService.getOverviewOrders());
    }

    @GetMapping("/overviewDishes")
    public Result<DishOverViewVO> overviewDishes() {
        return Result.success(workspaceService.getOverviewDishes());
    }

    @GetMapping("/overviewSetmeals")
    public Result<SetmealOverViewVO> overviewSetmeals() {
        return Result.success(workspaceService.getOverviewSetmeals());
    }
}