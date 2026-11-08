package com.sky.cache;

import com.sky.constant.RedisConstant;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 菜品缓存管理器。
 *
 * 统一生成菜品缓存 key，并把缓存删除延迟到数据库事务提交成功之后，
 * 避免事务回滚时 Redis 已经被提前修改。
 */
@Component
@Slf4j
public class DishCacheManager {

    static final long EMPTY_TTL_MINUTES = 5;
    static final long NORMAL_TTL_MINUTES = 30;
    static final long NORMAL_TTL_JITTER_BOUND = 11;

    private final RedisTemplate<String, Object> redisTemplate;

    public DishCacheManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成某个分类的菜品缓存 key。
     */
    public String categoryKey(Long categoryId) {
        return RedisConstant.DISH_CATEGORY_PREFIX + categoryId;
    }

    /**
     * 读取某个分类的菜品缓存。
     *
     * @return 命中时返回列表（包括空列表），未命中时返回 null
     */
    @SuppressWarnings("unchecked")
    public List<DishVO> getCategory(Long categoryId) {
        return (List<DishVO>) redisTemplate.opsForValue().get(categoryKey(categoryId));
    }

    /**
     * 回填某个分类的菜品缓存。
     *
     * 空列表只缓存 5 分钟，用于拦截对同一无效分类的重复查库，
     * 同时限制无效 key 占用内存的时间。正常列表缓存 30～40 分钟，
     * 使用随机抖动分散集中过期的时间点。
     */
    public void putCategory(Long categoryId, List<DishVO> dishes) {
        Objects.requireNonNull(dishes, "dishes must not be null");

        long ttlMinutes = dishes.isEmpty()
                ? EMPTY_TTL_MINUTES
                : NORMAL_TTL_MINUTES
                        + ThreadLocalRandom.current().nextLong(NORMAL_TTL_JITTER_BOUND);

        redisTemplate.opsForValue().set(
                categoryKey(categoryId),
                dishes,
                ttlMinutes,
                TimeUnit.MINUTES);
    }

    /**
     * 数据库事务提交成功后，精确删除受影响分类的菜品缓存。
     *
     * 如果调用处没有事务，则无法等待提交事件，只能立即删除。
     */
    public void evictCategoriesAfterCommit(Collection<Long> categoryIds) {
        // 1. HashSet 自动去重；同时忽略 null，避免生成 dish_null 这种无效 key
        Set<Long> uniqueCategoryIds = new HashSet<>();
        if (categoryIds != null) {
            for (Long categoryId : categoryIds) {
                if (categoryId != null) {
                    uniqueCategoryIds.add(categoryId);
                }
            }
        }

        // 2. 没有需要清理的分类时直接结束
        if (uniqueCategoryIds.isEmpty()) {
            return;
        }

        // 3. 当前存在 Spring 事务同步时，只注册回调，不立即操作 Redis
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictCategories(uniqueCategoryIds);
                }
            });
            return;
        }

        // 4. 非事务调用没有 afterCommit 时机，退化为立即删除
        evictCategories(uniqueCategoryIds);
    }

    private void evictCategories(Set<Long> categoryIds) {
        for (Long categoryId : categoryIds) {
            String key = categoryKey(categoryId);
            try {
                redisTemplate.delete(key);
            } catch (RuntimeException exception) {
                // MySQL 已经提交，此时不能再通过抛异常伪装成整个业务失败。
                // 逐个捕获还可以继续清理其他 key；后续需配合 TTL 或重试补偿自愈。
                log.error("菜品缓存删除失败，key={}", key, exception);
            }
        }
    }
}
