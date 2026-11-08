# Redis 菜品缓存一致性复盘

## 1. 这次修的不只是键名

原代码存在三类问题：

1. 用户端读取 `dish_{categoryId}`，管理端新增后却删除 `dish:{categoryId}`，两个 key 不是同一个 key。
2. 管理端在 Service 更改数据库之前删缓存。如果数据库事务随后回滚，Redis 不会跟着回滚。
3. 修改、删除、起售/停售通过 `KEYS dish_*` 找出全部菜品缓存再删除。`KEYS` 会遍历整个 keyspace，且没有必要删除未受影响的分类。

所以修复目标是：**统一 key，精确失效，且只在 MySQL 事务提交成功后删缓存。**

## 2. 最终时序

```text
管理员写请求
    → Service 开启 MySQL 事务
    → 保存受影响的分类 id
    → 校验业务规则
    → 修改 MySQL
    → 注册 afterCommit 回调
    → MySQL 提交成功
    → 精确删除 dish_{categoryId}
```

如果校验失败或事务回滚，`afterCommit()` 不会执行，Redis 保持原样。

## 3. 为什么要先保存分类 id

修改菜品时，菜品可能从分类 10 移到分类 20。只删新分类会使旧分类仍然显示该菜品，所以需要同时失效：

```java
Set<Long> affectedCategoryIds = new HashSet<>();
affectedCategoryIds.add(originalDish.getCategoryId());
affectedCategoryIds.add(dish.getCategoryId());
```

`Set<Long>` 表示元素为 `Long` 的集合，不保存重复值。如果修改前后都是分类 10，集合中仍只有一个 `10L`。

删除多个菜品时，Mapper 先执行：

```sql
select distinct category_id
from dish
where id in (?, ?, ?)
```

- `distinct` 在数据库侧对分类 id 去重。
- `#{id}` 在 MyBatis 中最终使用预编译参数 `?`，而不是把用户输入直接拼到 SQL 文本中。
- 必须在删除前查询，否则菜品行删掉后就无法再获取原分类 id。

## 4. `afterCommit` 是什么

`TransactionSynchronizationManager.registerSynchronization(...)` 把一个回调登记到当前 Spring 事务。匿名内部类只覆写我们需要的方法：

```java
new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        evictCategories(uniqueCategoryIds);
    }
}
```

这里的语法可拆成：

- `new TransactionSynchronization() { ... }`：创建一个实现该接口的匿名对象。
- `@Override`：声明下面方法覆写了接口定义，方法名写错时编译器会报错。
- `afterCommit()`：数据库事务已经提交后执行，它不能让 Redis 加入 MySQL 事务，只是改善操作时序。

## 5. 本轮测试证明了什么

`DishCacheManagerTest` 证明：

1. `10, 10, 20` 只删除 `dish_10` 和 `dish_20`。
2. 事务提交前不删 Redis。
3. 事务回滚不删 Redis。
4. 没有事务时退化为立即删除。
5. 一个 Redis key 删除失败时会记录错误并继续清理其他 key，不把已提交的 MySQL 操作伪装成整体失败。

`DishServiceImplTest` 证明：

1. 菜品换分类时同时传递旧、新分类。
2. 删除三道菜但只影响两个分类时，只失效两个分类缓存。
3. 菜品不存在或正在起售时，数据库删除和缓存删除都不执行。

当前完整 Maven 测试结果：14 个测试全部通过。

## 6. 仍然存在的边界

本轮不能被描述为“绝对一致”：

1. MySQL 提交后，Redis 删除仍可能因网络或 Redis 故障失败。
2. 菜品缓存目前没有 TTL，一次删除失败可能让旧数据长期存在。
3. 用户端的“未命中→查数据库→回填缓存”仍可能在特定并发时序下回填旧值。
4. 批量删除的校验仍逐个 `getById`，存在 N+1 查询，后续可改为批量查询。

下一步应优先加入合理 TTL（可附加小幅随机抖动），然后讨论 Redis 失效重试/补偿与并发回填问题。

## 7. 面试时如何讲

> 原项目的菜品缓存存在 key 不统一、先删缓存后改库、以及 `KEYS` 全量扫描问题。我统一了 key 常量，在 Service 事务中先记录受影响的分类，并通过 Spring 事务回调在 MySQL 提交后精确删除缓存。修改分类会同时失效旧、新 key，批量删除使用 `distinct` 保存分类 id。我用 7 个新单测覆盖提交、回滚、去重、换分类和校验失败场景。这是改善最终一致性，但不是分布式强一致；后续还需要 TTL 和失败补偿。

## 8. 自测题

1. 为什么修改菜品分类时要删两个 key？
2. 为什么 Redis 删除不能和 MySQL 一起自动回滚？
3. `HashSet` 和 SQL `distinct` 都能去重，本次为什么两处都保留？
4. 为什么这个方案只能称为改善最终一致性，不能称为强一致？

## 9. TTL 与空值缓存补充

用户端菜品列表现在按两类数据设置 TTL：

- 正常菜品列表：30～40 分钟随机 TTL，通过分散过期时间降低大量 key 集中失效引起的缓存雪崩风险。
- 空列表：5 分钟 TTL，用于拦截对同一无效分类的重复查库，同时限制无效 key 的内存占用与空结果的过期时间。

随机数语句：

```java
long ttlMinutes = 30
        + ThreadLocalRandom.current().nextLong(11);
```

`nextLong(11)` 返回 0～10，所以最终是 30～40 分钟。

读缓存也从 `hasKey + get` 改为单次 `get`：

```java
List<DishVO> list = dishCacheManager.getCategory(categoryId);
if (list != null) {
    return Result.success(list);
}
```

这里必须使用 `list != null` 判断是否命中。空列表 `[]` 是有意义的缓存命中；如果用 `list.isEmpty()` 判断，空值缓存就会失效。

当前 Maven 测试为 19/19，新覆盖了单次读取、空列表 TTL、随机 TTL 范围、空列表命中不查库和未命中回填。
