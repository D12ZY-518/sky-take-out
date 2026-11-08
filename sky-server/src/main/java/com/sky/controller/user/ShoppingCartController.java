package com.sky.controller.user;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;
import com.sky.result.Result;
import com.sky.service.ShoppingCartService;

import io.swagger.annotations.Api;

@RestController
@RequestMapping("/user/shoppingCart")
@Api(tags = "C端-购物车接口")
public class ShoppingCartController {
    
    @Autowired
    private ShoppingCartService shoppingCartService;
    /**
     * 添加购物车
     * @param shoppingCartDTO
     * @return
     */
    @PostMapping("/add")
    public Result<String> add(@RequestBody ShoppingCartDTO shoppingCartDTO) {
        shoppingCartService.addShoppingCart(shoppingCartDTO);
        return Result.success();
    }
    /**
     * 获取购物车列表
     * @return
     */
    @GetMapping("/list")
    public Result<List<ShoppingCart>> list() {
        return Result.success(shoppingCartService.showShoppingCart());
    }

    /**
     * 清空购物车
     * @return
     */
    @DeleteMapping("/clean")
    public Result<String> clean() {
        shoppingCartService.cleanShoppingCart();
        return Result.success();
    }
    
    /**
     * 减少购物车中的数据
     * @param shoppingCartDTO
     * @return
     */
    @PostMapping("/sub")
    public Result<String> sub(@RequestBody ShoppingCartDTO shoppingCartDTO) {
        shoppingCartService.subShoppingCart(shoppingCartDTO);
        return Result.success();    
    }   
}
