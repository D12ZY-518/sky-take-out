# 苍穹外卖 (Sky Take-Out)

> 一个基于 Spring Boot 的餐饮外卖后端系统，包含管理端和用户端两套接口。

## 🖥️ 项目展示

| 端 | 说明 | 地址 |
|---|---|---|
| 管理端 | 餐饮企业内部员工使用 | `http://localhost/` |
| 用户端 | 微信小程序消费者使用 | 小程序端 |
| 接口文档 | Knife4j 自动生成 | `http://localhost:8080/doc.html` |

**管理端测试账号**：`admin` / `123456`

## 🛠️ 技术栈

| 层级 | 技术 |
|------|------|
| 框架 | Spring Boot 2.x、Spring MVC、Spring Task |
| 持久层 | MyBatis、PageHelper、MySQL 8.x |
| 缓存 | Redis（Spring Data Redis） |
| 鉴权 | JWT + 拦截器（管理端/用户端双端鉴权） |
| AOP | 自定义注解 + Spring AOP（公共字段自动填充） |
| 实时通信 | WebSocket（来单提醒 + 催单推送） |
| 报表 | Apache POI（导出运营数据 Excel） |
| 文档 | Knife4j（Swagger 增强） |
| 对象存储 | 阿里云 OSS |
| 支付 | 微信支付（模拟） |
| 构建 | Maven 多模块 |
| 前端 | Vue.js + ElementUI（管理端）、微信小程序（用户端） |

## 📁 项目结构

```
sky-take-out/
├── sky-common/          # 公共模块：常量、异常、工具类、结果封装
├── sky-pojo/            # 数据对象：Entity、DTO、VO
├── sky-server/          # 服务模块：Controller、Service、Mapper、配置
└── pom.xml              # Maven 父工程
```

## 🚀 快速启动

### 环境要求

- JDK 8+
- Maven 3.6+
- MySQL 8.x
- Redis 5+
- Nginx (前端)

### 1. 数据库

```sql
-- 执行项目资料中的 sky.sql 建表脚本
source sky.sql;
```

修改 `application.yml` 中的数据库连接信息：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/sky
    username: root
    password: your_password
```

### 2. Redis

确保 Redis 服务已启动，默认连接 `localhost:6379`。

### 3. 启动后端

```bash
cd sky-take-out
mvn clean install -DskipTests
cd sky-server
mvn spring-boot:run
```

### 4. 启动前端

```bash
# 启动 Nginx（管理端前端）
cd nginx-1.20.2
start nginx.exe
```

## 🔑 核心功能

### 1. JWT 双端鉴权
- 管理端：`/admin/**` → `JwtTokenAdminInterceptor`
- 用户端：`/user/**` → `JwtTokenUserInterceptor`
- 解析用户 ID 存入 `ThreadLocal`，AOP 自动获取操作人

### 2. 下单核心链路
```
校验地址 & 购物车 → 插入订单主表 → 批量插入订单明细 → 清空购物车
```
全程 `@Transactional` 保证一致性，异常时全部回滚。

### 3. Redis 缓存策略
- **读**：Cache Aside 模式，先查 Redis，未命中查 MySQL 并写入缓存
- **写**：更新数据库后删除对应缓存
- 缓存菜品列表、套餐列表、店铺营业状态

### 4. WebSocket 实时推送
- 支付成功 → 来单提醒（type=1）
- 用户催单 → 催单推送（type=2）
- 使用 `ConcurrentHashMap` 管理会话，保证线程安全

### 5. 定时任务
- 每分钟检查超时未支付订单（15分钟），自动取消
- 每天凌晨处理滞留派送中的订单

### 6. AOP 公共字段自动填充
- 自定义 `@AutoFill` 注解标记 Mapper 方法
- 通过反射缓存优化，避免每次切面重复查找 Method 对象

## ✨ 个人优化改进

在原课程项目基础上做了以下改进：

| 优化项 | 说明 |
|--------|------|
| WebSocket 线程安全 | `HashMap` → `ConcurrentHashMap`，避免并发读写异常 |
| AOP 反射缓存 | 新增 `ConcurrentHashMap` 缓存 Method 对象，减少反射开销 |
| 全局异常处理增强 | 补充参数校验异常、JSON 格式异常和兜底异常处理 |
| Workspace 代码重构 | 消除原始类型警告、修复 Map 状态污染、抽取通用查询方法 |
| 日志规范 | WebSocket 由 `System.out` 改为 SLF4J 日志，支持统一日志管理 |

## 📖 项目来源

本项目基于黑马程序员课程项目「苍穹外卖」进行二次开发，在理解原项目架构和业务逻辑的基础上，独立完成了代码优化、重构和文档整理。

## 📝 License

本项目仅用于学习交流，不作为商业用途。
