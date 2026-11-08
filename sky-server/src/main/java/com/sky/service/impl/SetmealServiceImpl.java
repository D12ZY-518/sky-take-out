package com.sky.service.impl;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.entity.Dish;
import com.sky.entity.Setmeal;
import com.sky.entity.SetmealDish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.exception.SetmealEnableFailedException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import com.sky.mapper.SetmealMapper;
import com.sky.result.PageResult;
import com.sky.service.SetmealService;
import com.sky.vo.DishItemVO;
import com.sky.vo.SetmealVO;



@Service
public class SetmealServiceImpl implements SetmealService {
    
    @Autowired
    private SetmealMapper setmealMapper;

    @Autowired
    private SetmealDishMapper setmealDishMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private DishMapper dishMapper;
    
    @Transactional
    public void saveWithDish(SetmealDTO setmealDTO) {
        // 1. 拷贝 DTO 属性到实体，并补齐创建/更新时间与当前操作人（审计字段）
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        setmeal.setCreateTime(LocalDateTime.now());
        setmeal.setUpdateTime(LocalDateTime.now());
        setmeal.setCreateUser(BaseContext.getCurrentId());
        setmeal.setUpdateUser(BaseContext.getCurrentId());
        // 2. 先插入套餐主表，自增主键回填到 setmeal.id
        setmealMapper.insert(setmeal);
        // 3. 套餐明细非空才插入：逐条回填套餐 id 后批量写入关联表
        Long setmealId = setmeal.getId();
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && setmealDishes.size() > 0) {
            for (SetmealDish setmealDish : setmealDishes) {
                setmealDish.setSetmealId(setmealId);
            }
            setmealDishMapper.insertBatch(setmealDishes);
        }
    }

    public PageResult pageQuery(SetmealPageQueryDTO setmealPageQueryDTO) {
        PageHelper.startPage(setmealPageQueryDTO.getPage(), setmealPageQueryDTO.getPageSize());
        Page<SetmealVO> page = setmealMapper.pageQuery(setmealPageQueryDTO);
        return new PageResult( page.getTotal(),page.getResult());
    }

    @Transactional
    public void deleteByIds(List<Long> ids) {
        // 1. 逐个校验套餐状态：起售中的套餐不能删除（正在售卖/可下单的数据不允许删）
        for (Long id : ids) {
            Setmeal setmeal = setmealMapper.getById(id);
            if (setmeal.getStatus() != null && setmeal.getStatus() == StatusConstant.ENABLE) {
                throw new DeletionNotAllowedException(MessageConstant.SETMEAL_ON_SALE);
            }
        }       
        // 2. 校验全部通过才执行删除：先删套餐主记录，再删其关联的菜品明细
        setmealMapper.deleteByIds(ids);
        setmealDishMapper.deleteBySetmealIds(ids);
    }

    @Transactional
    public void update(SetmealDTO setmealDTO) {
        // 1. 先删除该套餐原有的全部菜品明细——先删后插，实现明细整体替换，避免旧数据残留
        setmealDishMapper.deleteBySetmealIds(Collections.singletonList(setmealDTO.getId()));
        // 2. 拷贝属性并补修改时间/操作人；状态置空：动态 SQL 不更新 null 字段，编辑套餐不会改动起售/停售状态
        Setmeal setmeal = new Setmeal();
        BeanUtils.copyProperties(setmealDTO, setmeal);
        setmeal.setUpdateTime(LocalDateTime.now());
        setmeal.setUpdateUser(BaseContext.getCurrentId());
        setmeal.setStatus(null);
        // 3. 更新套餐主表
        setmealMapper.update(setmeal);
        // 4. 新明细列表非空才重新插入：逐条回填套餐 id 后批量写入
        List<SetmealDish> setmealDishes = setmealDTO.getSetmealDishes();
        if (setmealDishes != null && setmealDishes.size() > 0) {
            for (SetmealDish setmealDish : setmealDishes) {
                setmealDish.setSetmealId(setmealDTO.getId());
            }
            setmealDishMapper.insertBatch(setmealDishes);
        }

    }

    public SetmealVO getByIdWithDish(Long id) {
        // 1. 查询套餐主记录：查不到直接抛业务异常（防止前端传无效 id 时后续空指针）
        Setmeal setmeal = setmealMapper.getById(id);
        if (setmeal == null) {
            throw new DeletionNotAllowedException(MessageConstant.SETMEAL_NOT_EXIST);
        }
        // 2. 组装 VO：复制套餐基础属性，并回查套餐包含的菜品明细与分类名称
        SetmealVO setmealVO = new SetmealVO();
        BeanUtils.copyProperties(setmeal, setmealVO);
        setmealVO.setSetmealDishes(setmealDishMapper.getBySetmealId(id));
        setmealVO.setCategoryName(categoryMapper.getById(setmeal.getCategoryId()).getName());
        return setmealVO;
    }

    public List<Setmeal> list(Setmeal setmeal) {
        return setmealMapper.list(setmeal);
    }

    public List<DishItemVO> getDishItemById(Long id) {
        return setmealMapper.getDishItemBySetmealId(id);
    }

    public void startOrStop(Integer status,Long id) {
         // 1. 起售前校验——套餐内的菜品必须全部在售（仅起售时需要：含停售菜品的套餐无法正常售卖）
        if (StatusConstant.ENABLE.equals(status)) {
            List<SetmealDish> setmealDishes = setmealDishMapper.getBySetmealId(id);
            for (SetmealDish setmealDish : setmealDishes) {
                Dish dish = dishMapper.getById(setmealDish.getDishId());
                if (dish.getStatus() == StatusConstant.DISABLE) {
                    throw new SetmealEnableFailedException(MessageConstant.SETMEAL_ENABLE_FAILED);
                }
            }
        }
    

        // 2. 起售、停售共用——更新套餐状态（对照你菜品版的最后一步），并填好修改时间与操作人
        Setmeal setmeal = new Setmeal();
        setmeal.setId(id);
        setmeal.setStatus(status);
        setmeal.setUpdateTime(LocalDateTime.now());
        setmeal.setUpdateUser(BaseContext.getCurrentId());
        setmealMapper.update(setmeal);
    }
}
