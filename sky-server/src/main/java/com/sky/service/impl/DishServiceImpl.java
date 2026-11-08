package com.sky.service.impl;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.cache.DishCacheManager;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.DishDTO;
import com.sky.dto.DishPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.DishFlavor;
import com.sky.entity.Setmeal;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.result.PageResult;
import com.sky.service.DishService;
import com.sky.vo.DishVO;

@Service
public class DishServiceImpl implements DishService {

    @Autowired
    private DishMapper dishMapper;

    @Autowired
    private DishFlavorMapper dishFlavorMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private DishCacheManager dishCacheManager;

    public PageResult pageQuery(DishPageQueryDTO dishPageQueryDTO) {
        PageHelper.startPage(dishPageQueryDTO.getPage(), dishPageQueryDTO.getPageSize());
        Page<DishVO> page = dishMapper.pageQuery(dishPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    @Transactional
    public void startOrStop(Integer status, Long id) {
        // 1. 修改前查询菜品，保存其分类 id；数据库更新后已无法仅凭更新参数知道缓存 key
        Dish originalDish = dishMapper.getById(id);
        if (originalDish == null) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_NOT_FOUND);
        }

        // 2. 更新菜品起售/停售状态
        Dish dish = new Dish();
        dish.setStatus(status);
        dish.setId(id);
        dish.setUpdateTime(LocalDateTime.now());
        dish.setUpdateUser(BaseContext.getCurrentId());
        dishMapper.update(dish);

        // 3. 事务提交成功后，精确删除当前分类的菜品缓存
        dishCacheManager.evictCategoriesAfterCommit(List.of(originalDish.getCategoryId()));
    }

    @Transactional
    public void saveWithFlavor(DishDTO dishDTO) {
        // 1. 拷贝 DTO 属性到实体，并补齐创建/更新时间与当前操作人（审计字段）
        Dish dish = new Dish();
        BeanUtils.copyProperties(dishDTO, dish);
        dish.setUpdateTime(LocalDateTime.now());
        dish.setUpdateUser(BaseContext.getCurrentId());
        dish.setCreateTime(LocalDateTime.now());
        dish.setCreateUser(BaseContext.getCurrentId());
        // 2. 新增菜品默认置为停售（0）：须后台手动起售，避免数据未备好就出现在售卖端
        dish.setStatus(StatusConstant.DISABLE);
        // 3. 先插入菜品主表，自增主键回填到 dish.id，供口味关联使用
        dishMapper.insert(dish);
        // 4. 口味列表非空才处理：给每个口味回填所属菜品 id 后批量插入（口味表是菜品的从表）
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && flavors.size() > 0) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dish.getId());
            });
            dishFlavorMapper.insertBatch(flavors);
        }

        // 5. 新增成功提交后，只需失效新菜品所属分类的缓存
        dishCacheManager.evictCategoriesAfterCommit(List.of(dish.getCategoryId()));
    }

    public DishVO getByIdWithFlavor(Long id) {
        // 1. 查询菜品主记录：查不到直接抛业务异常（防止前端传无效 id 时后续空指针）
        DishVO dishVO = new DishVO();
        Dish dish = dishMapper.getById(id);
        if (dish == null) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_NOT_FOUND); 
        }
        // 2. 组装展示 VO：复制菜品基础属性，并回查口味列表与分类名称
        BeanUtils.copyProperties(dish, dishVO);
        dishVO.setFlavors(dishFlavorMapper.getByDishId(id));
        dishVO.setCategoryName(categoryMapper.getById(dish.getCategoryId()).getName());
        return dishVO;
    }

    @Transactional
    public void updateWithFlavor(DishDTO dishDTO) {
        // 1. 先查原菜品，保存旧分类 id；菜品可能从一个分类移动到另一个分类
        Dish originalDish = dishMapper.getById(dishDTO.getId());
        if (originalDish == null) {
            throw new DeletionNotAllowedException(MessageConstant.DISH_NOT_FOUND);
        }

        // 2. 拷贝 DTO 属性到实体，并设置状态为 null：动态 SQL 不更新 null 字段，编辑菜品不会改动起售/停售状态
        Dish dish = new Dish();       
        BeanUtils.copyProperties(dishDTO, dish);
        dish.setStatus(null);
        // 3. 填充本次修改的审计字段：修改时间、操作人
        dish.setUpdateTime(LocalDateTime.now());
        dish.setUpdateUser(BaseContext.getCurrentId());
        // 4. 先删除该菜品原有全部口味——再按新列表重插，先删后插实现口味整体替换，避免旧口味残留
        dishFlavorMapper.deleteByDishIds(List.of(dish.getId()));
        // 5. 新口味列表非空才处理：给每个口味回填菜品 id 后批量插入
        List<DishFlavor> flavors = dishDTO.getFlavors();
        if (flavors != null && !flavors.isEmpty()) {
            flavors.forEach(dishFlavor -> {
                dishFlavor.setDishId(dish.getId());
            });
            dishFlavorMapper.insertBatch(flavors);
        }       
        // 6. 最后更新菜品主表的基本信息
        dishMapper.update(dish);

        // 7. 同时记录旧分类和新分类；HashSet 在分类未变化时会自动去重
        Set<Long> affectedCategoryIds = new HashSet<>();
        affectedCategoryIds.add(originalDish.getCategoryId());
        affectedCategoryIds.add(dish.getCategoryId());
        dishCacheManager.evictCategoriesAfterCommit(affectedCategoryIds);
    }

    public List<DishVO> list(Long categoryId) {
        List<Dish> dishList = dishMapper.listByCategoryId(categoryId);
        List<DishVO> dishVOList = dishList.stream().map(dish -> {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(dish, dishVO);
            return dishVO;
        }).toList();
        return dishVOList;
    }


    @Transactional
    public void deleteByIds(List<Long> ids) {
        // 1. 空集合不生成 where id in ()，直接结束
        if (ids == null || ids.isEmpty()) {
            return;
        }

        // 2. 删除前批量保存受影响的分类 id；SQL 使用 distinct 去重
        List<Long> affectedCategoryIds = dishMapper.listCategoryIdsByDishIds(ids);

        // 3. 业务校验（操作前把规则查清楚）：逐个检查，起售中的菜品不能删除
        for (Long id : ids) {
            Dish dish = dishMapper.getById(id);
            if (dish == null) {
                throw new DeletionNotAllowedException(MessageConstant.DISH_NOT_FOUND);
            }
            if (dish.getStatus() != null && dish.getStatus() == StatusConstant.ENABLE) {
                throw new DeletionNotAllowedException(MessageConstant.DISH_ON_SALE);  // "起售中的菜品不能删除"
            }
        }
        // 4. 继续校验：被任意套餐关联（含停售的套餐）的菜品也不能删除
        List<Long> setmealIds = setmealDishMapper.getSetmealIdsByDishIds(ids);
        if (setmealIds != null && setmealIds.size() > 0) {
            // 被任意套餐关联（含停售）就不能删——套餐之后可能重新上架，删了菜品套餐就废了
            throw new DeletionNotAllowedException(MessageConstant.DISH_BE_RELATED_BY_SETMEAL); 
        }
        // 5. 两道校验都通过才真正执行删除：先删菜品主记录，再删其口味数据
        dishMapper.deleteByIds(ids);
        dishFlavorMapper.deleteByDishIds(ids);

        // 6. 事务提交成功后，精确删除所有受影响分类的缓存
        dishCacheManager.evictCategoriesAfterCommit(affectedCategoryIds);
    }

    public List<DishVO> listWithFlavor(Dish dish) {
        List<Dish> dishList = dishMapper.list(dish);
        List<DishVO> dishVOList = dishList.stream().map(item -> {
            DishVO dishVO = new DishVO();
            BeanUtils.copyProperties(item, dishVO);
            dishVO.setFlavors(dishFlavorMapper.getByDishId(item.getId()));
            dishVO.setCategoryName(categoryMapper.getById(item.getCategoryId()).getName());
            return dishVO;
        }).toList();
        return dishVOList;
    }
    
}
