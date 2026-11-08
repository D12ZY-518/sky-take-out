package com.sky.controller.user;

import com.sky.cache.DishCacheManager;
import com.sky.entity.Dish;
import com.sky.result.Result;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DishControllerTest {

    private DishService dishService;
    private DishCacheManager dishCacheManager;
    private DishController dishController;

    @BeforeEach
    void setUp() {
        dishService = mock(DishService.class);
        dishCacheManager = mock(DishCacheManager.class);
        dishController = new DishController();
        ReflectionTestUtils.setField(dishController, "dishService", dishService);
        ReflectionTestUtils.setField(dishController, "dishCacheManager", dishCacheManager);
    }

    @Test
    void emptyCachedListShouldCountAsHitAndSkipDatabase() {
        when(dishCacheManager.getCategory(10L)).thenReturn(List.of());

        Result<List<DishVO>> result = dishController.list(10L);

        assertEquals(List.of(), result.getData());
        verify(dishService, never()).listWithFlavor(any());
        verify(dishCacheManager, never()).putCategory(anyLong(), anyList());
    }

    @Test
    void cacheMissShouldQueryDatabaseAndPopulateCache() {
        List<DishVO> dishes = List.of(new DishVO());
        when(dishCacheManager.getCategory(10L)).thenReturn(null);
        when(dishService.listWithFlavor(argThat(this::isEnabledCategoryTen))).thenReturn(dishes);

        Result<List<DishVO>> result = dishController.list(10L);

        assertEquals(dishes, result.getData());
        verify(dishCacheManager).putCategory(10L, dishes);
    }

    private boolean isEnabledCategoryTen(Dish dish) {
        return dish != null
                && Long.valueOf(10L).equals(dish.getCategoryId())
                && Integer.valueOf(1).equals(dish.getStatus());
    }
}
