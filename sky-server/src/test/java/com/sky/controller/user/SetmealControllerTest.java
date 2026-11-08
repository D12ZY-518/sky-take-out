package com.sky.controller.user;

import com.sky.constant.StatusConstant;
import com.sky.entity.Setmeal;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SetmealControllerTest {

    private SetmealService setmealService;
    private SetmealController setmealController;

    @BeforeEach
    void setUp() {
        setmealService = mock(SetmealService.class);
        setmealController = new SetmealController();
        ReflectionTestUtils.setField(setmealController, "setmealService", setmealService);
    }

    @Test
    void nullOrNonPositiveCategoryIdShouldReturnErrorWithoutQueryingDatabase() {
        for (Long invalidCategoryId : Arrays.asList(null, 0L, -1L)) {
            Result<List<Setmeal>> result = setmealController.list(invalidCategoryId);

            assertEquals(0, result.getCode());
            assertEquals("分类 ID 必须为正数", result.getMsg());
        }
        verify(setmealService, never()).list(any());
    }

    @Test
    void validCategoryIdShouldQueryEnabledSetmealsOnly() {
        List<Setmeal> expected = List.of(new Setmeal());
        when(setmealService.list(argThat(this::isEnabledCategoryTen))).thenReturn(expected);

        Result<List<Setmeal>> result = setmealController.list(10L);

        assertEquals(1, result.getCode());
        assertEquals(expected, result.getData());
        verify(setmealService).list(argThat(this::isEnabledCategoryTen));
    }

    @Test
    void cacheAnnotationShouldOnlyApplyToPositiveCategoryIds() throws NoSuchMethodException {
        Method method = SetmealController.class.getMethod("list", Long.class);
        Cacheable cacheable = method.getAnnotation(Cacheable.class);

        assertEquals("#categoryId", cacheable.key());
        assertEquals("#categoryId != null && #categoryId > 0", cacheable.condition());
    }

    private boolean isEnabledCategoryTen(Setmeal query) {
        return query != null
                && Long.valueOf(10L).equals(query.getCategoryId())
                && Integer.valueOf(StatusConstant.ENABLE).equals(query.getStatus())
                && query.getName() == null;
    }
}
