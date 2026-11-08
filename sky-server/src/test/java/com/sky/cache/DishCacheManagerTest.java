package com.sky.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

import com.sky.vo.DishVO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DishCacheManagerTest {

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldEvictDistinctCategoryKeysOnlyAfterCommit() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);
        TransactionSynchronizationManager.initSynchronization();

        cacheManager.evictCategoriesAfterCommit(List.of(10L, 10L, 20L));

        verify(redisTemplate, never()).delete("dish_10");
        verify(redisTemplate, never()).delete("dish_20");

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(redisTemplate).delete("dish_10");
        verify(redisTemplate).delete("dish_20");
    }

    @Test
    void shouldNotEvictWhenTransactionRollsBack() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);
        TransactionSynchronizationManager.initSynchronization();

        cacheManager.evictCategoriesAfterCommit(List.of(10L));

        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(redisTemplate, never()).delete("dish_10");
    }

    @Test
    void shouldEvictImmediatelyWithoutTransaction() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);

        cacheManager.evictCategoriesAfterCommit(List.of(10L));

        verify(redisTemplate).delete("dish_10");
    }

    @Test
    void shouldContinueEvictingOtherKeysWhenOneRedisDeleteFails() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);
        doThrow(new RuntimeException("Redis unavailable"))
                .when(redisTemplate).delete("dish_10");

        cacheManager.evictCategoriesAfterCommit(List.of(10L, 20L));

        verify(redisTemplate).delete("dish_10");
        verify(redisTemplate).delete("dish_20");
    }

    @Test
    void shouldReturnCachedCategoryWithSingleGet() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        List<DishVO> cachedDishes = List.of(new DishVO());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("dish_10")).thenReturn(cachedDishes);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);

        List<DishVO> result = cacheManager.getCategory(10L);

        assertEquals(cachedDishes, result);
        verify(valueOperations).get("dish_10");
    }

    @Test
    void shouldCacheEmptyCategoryForFiveMinutes() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);

        cacheManager.putCategory(10L, List.of());

        verify(valueOperations).set(
                "dish_10",
                List.of(),
                DishCacheManager.EMPTY_TTL_MINUTES,
                TimeUnit.MINUTES);
    }

    @Test
    void shouldCacheNormalCategoryWithRandomizedTtl() {
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        DishCacheManager cacheManager = new DishCacheManager(redisTemplate);
        List<DishVO> dishes = List.of(new DishVO());

        cacheManager.putCategory(10L, dishes);

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOperations).set(
                eq("dish_10"),
                eq(dishes),
                ttlCaptor.capture(),
                eq(TimeUnit.MINUTES));
        assertTrue(ttlCaptor.getValue() >= 30L && ttlCaptor.getValue() <= 40L);
    }
}
