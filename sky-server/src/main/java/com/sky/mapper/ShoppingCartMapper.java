package com.sky.mapper;

import com.sky.entity.ShoppingCart;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 购物车数据访问层
 */
@Mapper
public interface ShoppingCartMapper {
    /**
     * 新增购物车
     * @param shoppingCart
     */
    @Insert("insert into shopping_cart(name,user_id,dish_id,setmeal_id,dish_flavor,number,amount,image,create_time) values(#{name},#{userId},#{dishId},#{setmealId},#{dishFlavor},#{number},#{amount},#{image},#{createTime})")
    void insert(ShoppingCart shoppingCart);

    /**
     * 查询购物车
     * @return
     */
    List<ShoppingCart> list(ShoppingCart shoppingCart);

    /**
     * 删除购物车
     * @param id
     */
    @Delete("delete from shopping_cart where id=#{id}")
    void deleteById(Long id);

    /**
     * 修改购物车
     * @param shoppingCart
     */
    @Update("update shopping_cart set number=#{number} where id=#{id}")
    void updateNumberById(ShoppingCart shoppingCart);

    /**
     * 删除购物车
     * @param userId
     */
    @Delete("delete from shopping_cart where user_id=#{userId}")
    void deleteByUserId(Long userId);   

    void insertBatch(List<ShoppingCart> shoppingCarts);  
}
