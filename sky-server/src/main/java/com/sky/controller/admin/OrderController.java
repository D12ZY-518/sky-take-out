package com.sky.controller.admin;

import io.swagger.annotations.Api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.OrderService;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderVO;

@RestController("adminOrderController")   // 必须命名！防与 user 包 OrderController 冲突
@RequestMapping("/admin/order")
@Api(tags = "管理端-订单管理接口")
public class OrderController {
    @Autowired
    private OrderService orderService;

    
    @GetMapping("/conditionSearch")
    public Result<PageResult> conditionSearch(OrdersPageQueryDTO dto) {
        return Result.success(orderService.conditionSearch(dto));
    }
    
    @GetMapping("/statistics")
    public Result<OrderStatisticsVO> statistics() {
        return Result.success(orderService.statistics());
    }

    @GetMapping("/details/{id}")
    public Result<OrderVO> details(@PathVariable Long id) {
        return Result.success(orderService.adminDetails(id));
    }
    
    @PutMapping("/confirm")
    public Result<String> confirm(@RequestBody OrdersConfirmDTO dto) {
        orderService.confirm(dto);
        return Result.success("确认成功");
    }
    
    @PutMapping("/rejection")
    public Result<String> rejection(@RequestBody OrdersRejectionDTO dto) throws Exception {
        orderService.rejection(dto);
        return Result.success("拒绝成功");
    }
    
    @PutMapping("/cancel")
    public Result<String> cancel(@RequestBody OrdersCancelDTO dto) throws Exception {
        orderService.cancel(dto);
        return Result.success("取消成功");
    }
    
    @PutMapping("/delivery/{id}")
    public Result<String> delivery(@PathVariable Long id) {
        orderService.delivery(id);
        return Result.success("派送成功");
    }
    
    @PutMapping("/complete/{id}")
    public Result<String> complete(@PathVariable Long id) {
        orderService.complete(id);
        return Result.success("完成成功");
    }   
}
