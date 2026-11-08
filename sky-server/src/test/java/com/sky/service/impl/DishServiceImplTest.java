package com.sky.service.impl;

import com.sky.cache.DishCacheManager;
import com.sky.constant.MessageConstant;
import com.sky.constant.StatusConstant;
import com.sky.dto.DishDTO;
import com.sky.entity.Dish;
import com.sky.exception.DeletionNotAllowedException;
import com.sky.mapper.CategoryMapper;
import com.sky.mapper.DishFlavorMapper;
import com.sky.mapper.DishMapper;
import com.sky.mapper.SetmealDishMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DishServiceImplTest {

    @Mock
    private DishMapper dishMapper;

    @Mock
    private DishFlavorMapper dishFlavorMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private SetmealDishMapper setmealDishMapper;

    @Mock
    private DishCacheManager dishCacheManager;

    @InjectMocks
    private DishServiceImpl dishService;

    @Test
    void updateShouldEvictBothOldAndNewCategoryCaches() {
        Dish originalDish = new Dish();
        originalDish.setId(1L);
        originalDish.setCategoryId(10L);
        when(dishMapper.getById(1L)).thenReturn(originalDish);

        DishDTO dishDTO = new DishDTO();
        dishDTO.setId(1L);
        dishDTO.setCategoryId(20L);
        dishDTO.setFlavors(List.of());

        dishService.updateWithFlavor(dishDTO);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> categoryIdsCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(dishCacheManager).evictCategoriesAfterCommit(categoryIdsCaptor.capture());
        assertEquals(Set.of(10L, 20L), Set.copyOf(categoryIdsCaptor.getValue()));
    }

    @Test
    void deleteShouldEvictOnlyAffectedCategoryCachesAfterValidation() {
        Dish firstDish = dish(1L, 10L, StatusConstant.DISABLE);
        Dish secondDish = dish(2L, 10L, StatusConstant.DISABLE);
        Dish thirdDish = dish(3L, 20L, StatusConstant.DISABLE);
        List<Long> dishIds = List.of(1L, 2L, 3L);

        when(dishMapper.listCategoryIdsByDishIds(dishIds)).thenReturn(List.of(10L, 20L));
        when(dishMapper.getById(1L)).thenReturn(firstDish);
        when(dishMapper.getById(2L)).thenReturn(secondDish);
        when(dishMapper.getById(3L)).thenReturn(thirdDish);
        when(setmealDishMapper.getSetmealIdsByDishIds(dishIds)).thenReturn(List.of());

        dishService.deleteByIds(dishIds);

        verify(dishMapper).deleteByIds(dishIds);
        verify(dishFlavorMapper).deleteByDishIds(dishIds);
        verify(dishCacheManager).evictCategoriesAfterCommit(List.of(10L, 20L));
    }

    @Test
    void deleteShouldStopBeforeDatabaseAndCacheMutationWhenDishDoesNotExist() {
        List<Long> dishIds = List.of(99L);
        when(dishMapper.listCategoryIdsByDishIds(dishIds)).thenReturn(List.of());
        when(dishMapper.getById(99L)).thenReturn(null);

        DeletionNotAllowedException exception = assertThrows(
                DeletionNotAllowedException.class,
                () -> dishService.deleteByIds(dishIds));

        assertEquals(MessageConstant.DISH_NOT_FOUND, exception.getMessage());
        verify(dishMapper, never()).deleteByIds(any());
        verify(dishFlavorMapper, never()).deleteByDishIds(any());
        verify(dishCacheManager, never()).evictCategoriesAfterCommit(any());
    }

    @Test
    void deleteShouldStopBeforeDatabaseAndCacheMutationWhenDishIsOnSale() {
        List<Long> dishIds = List.of(1L);
        when(dishMapper.listCategoryIdsByDishIds(dishIds)).thenReturn(List.of(10L));
        when(dishMapper.getById(1L)).thenReturn(dish(1L, 10L, StatusConstant.ENABLE));

        DeletionNotAllowedException exception = assertThrows(
                DeletionNotAllowedException.class,
                () -> dishService.deleteByIds(dishIds));

        assertEquals(MessageConstant.DISH_ON_SALE, exception.getMessage());
        verify(dishMapper, never()).deleteByIds(any());
        verify(dishFlavorMapper, never()).deleteByDishIds(any());
        verify(dishCacheManager, never()).evictCategoriesAfterCommit(any());
    }

    private Dish dish(Long id, Long categoryId, Integer status) {
        Dish dish = new Dish();
        dish.setId(id);
        dish.setCategoryId(categoryId);
        dish.setStatus(status);
        return dish;
    }
}
