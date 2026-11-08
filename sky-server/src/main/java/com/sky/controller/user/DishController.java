package com.sky.controller.user;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.cache.DishCacheManager;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;

@RestController("userDishController")
@RequestMapping("/user/dish")
public class DishController {
    
    @Autowired
    private DishService dishService;

    @Autowired
    private DishCacheManager dishCacheManager;

    @GetMapping("/list")
    public Result<List<DishVO>> list(Long categoryId) {
        // 1. 直接 get 只需一次 Redis 请求；非 null 表示命中，空列表也是有效缓存
        List<DishVO> list = dishCacheManager.getCategory(categoryId);
        if (list != null) {
            return Result.success(list);
        }

        // 2. 未命中时查询 MySQL
        Dish dish = new Dish();
        dish.setCategoryId(categoryId);
        dish.setStatus(StatusConstant.ENABLE);
        list = dishService.listWithFlavor(dish);

        // 3. 空列表和正常列表都回填，由缓存管理器选择不同 TTL
        dishCacheManager.putCategory(categoryId, list);
        return Result.success(list);
    }
}
