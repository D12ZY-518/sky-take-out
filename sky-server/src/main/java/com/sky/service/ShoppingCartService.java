package com.sky.service;

import java.util.List;

import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.ShoppingCart;

public interface ShoppingCartService {

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    void addShoppingCart(ShoppingCartDTO shoppingCartDTO);

    /**
     * 减少购物车数量
     * @param shoppingCartDTO
     */
    void subShoppingCart(ShoppingCartDTO shoppingCartDTO);
    /**
     * 查询购物车
     * @param userId
     * @return
     */
    List<ShoppingCart> showShoppingCart();
    /**
     * 清空购物车
     * @param userId
     */
    void cleanShoppingCart();

}
