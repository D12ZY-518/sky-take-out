package com.sky.controller.user;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sky.constant.StatusConstant;
import com.sky.entity.Setmeal;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;

@RestController("userSetmealController")
@RequestMapping("/user/setmeal")
public class SetmealController {
    
    @Autowired
    private SetmealService setmealService;
    
    @Cacheable(
            cacheNames = "setmealCache",
            key = "#categoryId",
            condition = "#categoryId != null && #categoryId > 0"
    )
    @GetMapping("/list")
    public Result<List<Setmeal>> list(@RequestParam("categoryId") Long categoryId) {
        if (categoryId == null || categoryId <= 0) {
            return Result.error("分类 ID 必须为正数");
        }

        Setmeal query = new Setmeal();
        query.setCategoryId(categoryId);
        query.setStatus(StatusConstant.ENABLE);
        return Result.success(setmealService.list(query));
    }

    @GetMapping("/dish/{id}")
    public Result<List<DishItemVO>> dish(@PathVariable Long id) {
        return Result.success(setmealService.getDishItemById(id));
    }
}
