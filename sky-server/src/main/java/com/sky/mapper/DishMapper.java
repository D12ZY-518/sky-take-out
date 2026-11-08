package com.sky.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import com.github.pagehelper.Page;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.vo.DishVO;

@Mapper
public interface DishMapper {

    /**
     * 根据分类id查询菜品数量
     * @param categoryId
     * @return
     */
    @Select("select count(id) from dish where category_id = #{categoryId}")
    Integer countByCategoryId(Long categoryId);

    /**
     * 插入菜品
     * @param dish
     */
    @Insert("insert into dish (name, category_id, price, status, create_time, update_time, create_user, update_user, image, description) values (#{name}, #{categoryId}, #{price}, #{status}, #{createTime}, #{updateTime}, #{createUser}, #{updateUser}, #{image}, #{description})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(Dish dish);

    /**
     * 菜品分页查询
     * @param dishPageQueryDTO
     * @return
     */
    Page<DishVO> pageQuery(DishPageQueryDTO dishPageQueryDTO);

    /**
     * 更新菜品
     * @param dish
     */
    void update(Dish dish);

    /**
     * 批量删除菜品
     * @param ids
     */
    void deleteByIds(@Param("ids") List<Long> ids);

    /**
     * 根据菜品 id 批量查询其所属分类 id。
     *
     * 删除菜品前先保存这些分类 id，事务提交成功后才能精确失效对应分类缓存。
     * SQL 使用 distinct 在数据库侧去重，避免同一分类 id 重复返回。
     *
     * @param ids 菜品 id 集合
     * @return 去重后的分类 id 列表
     */
    List<Long> listCategoryIdsByDishIds(@Param("ids") List<Long> ids);

    /**
     * 根据id查询菜品和对应的口味数据
     * @param id
     * @return
     */
    @Select("select * from dish where id = #{id}")
    Dish getById(Long id);

    /**
     * 根据分类id查询菜品
     * @param categoryId
     * @return
     */
    @Select("select * from dish where category_id = #{categoryId}")
    List<Dish> listByCategoryId(@Param("categoryId") Long categoryId);

    /**
     * 条件查询菜品
     * @param dish
     * @return
     */
    List<Dish> list(Dish dish);

}
