package com.sky.controller.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.sky.constant.RedisConstant;
import com.sky.constant.StatusConstant;
import com.sky.result.Result;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;

@RestController("userShopController")          // ← 为什么要带名字？（想想第二个 ShopController 的到来）
@RequestMapping("/user/shop")          // ← 路径前缀是什么？
@Api(tags = "C端相关接口")          // ← Swagger 文档的标签是什么？
@Slf4j
public class ShopController {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;           // ← 模板类 + 变量名

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