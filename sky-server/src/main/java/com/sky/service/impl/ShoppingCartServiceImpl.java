package com.sky.service.impl;

import org.springframework.stereotype.Service;

import com.sky.context.BaseContext;
import com.sky.dto.ShoppingCartDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.ShoppingCart;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.service.ShoppingCartService;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ShoppingCartServiceImpl implements ShoppingCartService {
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;  // ← 创建数据层对象

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private SetmealMapper setmealMapper;

    /**
     * 查询购物车
     * @param userId
     * @return
     */
    @Override
    public List<ShoppingCart> showShoppingCart() {
        return shoppingCartMapper.list(ShoppingCart.builder().userId(BaseContext.getCurrentId()).build());
    }

    /**
     * 添加购物车
     * @param shoppingCartDTO
     */
    @Override
    public void addShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        // 1. 拷贝入参并绑定当前登录用户，购物车数据按用户隔离
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        shoppingCart.setUserId(BaseContext.getCurrentId());
        // 2. 按当前用户 + 菜品/套餐（含口味）精确查询，判断该商品是否已在购物车中
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        if (!list.isEmpty()) {
            // 3. 已存在：数量 +1，更新后返回（同一商品合并为一条记录，只累加数量）
            shoppingCart = list.get(0);
            shoppingCart.setNumber(shoppingCart.getNumber() + 1);
            shoppingCartMapper.updateNumberById(shoppingCart);
            return;
        }
        // 4. 不存在：判断是菜品还是套餐，填充名称/价格/图片
        if (shoppingCartDTO.getDishId() != null) {
            Dish dish = dishMapper.getById(shoppingCartDTO.getDishId());
            shoppingCart.setName(dish.getName());
            shoppingCart.setAmount(dish.getPrice());
            shoppingCart.setImage(dish.getImage());
        } else {
            Setmeal setmeal = setmealMapper.getById(shoppingCartDTO.getSetmealId());
            shoppingCart.setName(setmeal.getName());
            shoppingCart.setAmount(setmeal.getPrice());
            shoppingCart.setImage(setmeal.getImage());
        }
        // 5. 新加购商品数量初始为 1，记录创建时间后插入购物车
        shoppingCart.setNumber(1);
        shoppingCart.setCreateTime(LocalDateTime.now());
        shoppingCartMapper.insert(shoppingCart);
    }

    /**
     * 删除购物车
     * @param shoppingCartDTO
     */
    @Override
    public void subShoppingCart(ShoppingCartDTO shoppingCartDTO) {
        // 1. 拷贝入参并绑定当前登录用户，查出该商品在购物车中的记录
        ShoppingCart shoppingCart = new ShoppingCart();
        BeanUtils.copyProperties(shoppingCartDTO, shoppingCart);
        shoppingCart.setUserId(BaseContext.getCurrentId());
        List<ShoppingCart> list = shoppingCartMapper.list(shoppingCart);
        // 2. 记录不存在则直接返回（正常流程不会走到，属兜底保护）
        if (list.isEmpty()) {
            return;
        }
        // 3. 数量为 1 时删除整条记录；大于 1 时数量减 1（减到 0 的记录无意义，直接移除）
        shoppingCart = list.get(0);
        if (shoppingCart.getNumber() == 1) {
            shoppingCartMapper.deleteById(shoppingCart.getId());
        } else {
            shoppingCart.setNumber(shoppingCart.getNumber() - 1);
            shoppingCartMapper.updateNumberById(shoppingCart);
        }
    }

    /**
     * 清空购物车
     * @param userId
     */
    @Override
    public void cleanShoppingCart() {
        shoppingCartMapper.deleteByUserId(BaseContext.getCurrentId());
    }
}
