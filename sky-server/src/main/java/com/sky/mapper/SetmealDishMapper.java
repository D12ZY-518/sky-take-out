package com.sky.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.SetmealDish;

@Mapper
public interface SetmealDishMapper {

    /**
     * 根据套餐id删除套餐菜品关系
     * @param setmealId
     */
    @Delete("<script>" +
    "delete from setmeal_dish where setmeal_id in " +
    "<foreach collection='setmealIds' item='setmealId' open='(' separator=',' close=')'>" +
    "#{setmealId}" +
    "</foreach>" +
    "</script>")
    void deleteBySetmealIds(@Param("setmealIds") List<Long> setmealIds);

    /**
     * 根据菜品id查询套餐id
     * @param dishIds
     * @return
     */
    @Select("<script>" +
        "select setmeal_id from setmeal_dish where dish_id in " +
        "<foreach collection='dishIds' item='id' open='(' separator=',' close=')'>" +
        "#{id}" +
        "</foreach>" +
        "</script>")
    List<Long> getSetmealIdsByDishIds(@Param("dishIds") List<Long> dishIds);

     /**
     * 批量插入套餐和菜品的关联关系
     * @param setmealDishes
     */
    @Insert("<script>" +
            "insert into setmeal_dish (setmeal_id, dish_id, name, price, copies) values " +
            "<foreach collection=\"setmealDishes\" item=\"setmealDish\" separator=\",\">" +
            "(#{setmealDish.setmealId}, #{setmealDish.dishId}, #{setmealDish.name}, #{setmealDish.price}, #{setmealDish.copies})" +
            "</foreach>" +
            "</script>")
    void insertBatch(@Param("setmealDishes") List<SetmealDish> setmealDishes);

    /**
     * 根据套餐id查询套餐数据
     * @param setmealId
     * @return
     */
    @Select("select * from setmeal_dish where setmeal_id = #{setmealId}")
    List<SetmealDish> getBySetmealId(Long setmealId);
}
