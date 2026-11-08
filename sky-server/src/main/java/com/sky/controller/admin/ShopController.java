package com.sky.controller.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.sky.constant.RedisConstant;
import com.sky.constant.StatusConstant;
import com.sky.result.Result;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;

@RestController("adminShopController")          // ← 为什么要带名字？（想想第二个 ShopController 的到来）
@RequestMapping("/admin/shop")          // ← 路径前缀是什么？
@Api(tags = "商铺相关接口")          // ← Swagger 文档的标签是什么？
@Slf4j
public class ShopController {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;           // ← 模板类 + 变量名

    // 设置营业状态：PUT + Path 变量 status
    //   —— SetmealController 的起售停售写法刚练过，照那个轮廓
    //   —— 存 Redis：key 用上面问题 3 的常量，value 注意 String
    @PutMapping("/{status}")
    @ApiOperation("设置营业状态")
    public Result<String> setStatus(@PathVariable Integer status) { 
        stringRedisTemplate.opsForValue().set(RedisConstant.SHOP_STATUS, status.toString());
        return Result.success();
    }

    // 获取营业状态：GET + 返回 Result<Integer>
    //   —— get 出来的是 String，返回类型却是 Integer，中间要做什么转换？
    @GetMapping("/status")
    @ApiOperation("获取营业状态")
    public Result<Integer> getStatus() { 
        String status = (String) stringRedisTemplate.opsForValue().get(RedisConstant.SHOP_STATUS);
        if (status == null) {
            return Result.success(StatusConstant.DISABLE);
        }
        Integer statusInt = Integer.parseInt(status);
        return Result.success(statusInt);
    }
}