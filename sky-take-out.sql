-- 创建 sky_take_out 数据库
CREATE DATABASE IF NOT EXISTS sky_take_out DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE sky_take_out;

-- 员工表
DROP TABLE IF EXISTS `employee`;
CREATE TABLE `employee` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) NOT NULL COMMENT '姓名',
  `username` varchar(32) NOT NULL COMMENT '用户名',
  `password` varchar(64) NOT NULL COMMENT '密码',
  `phone` varchar(11) NOT NULL COMMENT '手机号',
  `sex` varchar(2) NOT NULL COMMENT '性别',
  `id_number` varchar(18) NOT NULL COMMENT '身份证号',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '状态 0:禁用，1:启用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `create_user` bigint(20) NOT NULL COMMENT '创建人',
  `update_user` bigint(20) NOT NULL COMMENT '修改人',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工';

-- 用户表
DROP TABLE IF EXISTS `user`;
CREATE TABLE `user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid` varchar(45) DEFAULT NULL COMMENT '微信用户唯一标识',
  `name` varchar(50) DEFAULT NULL COMMENT '姓名',
  `phone` varchar(11) DEFAULT NULL COMMENT '手机号',
  `sex` varchar(2) DEFAULT NULL COMMENT '性别',
  `avatar` varchar(500) DEFAULT NULL COMMENT '头像',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 分类表
DROP TABLE IF EXISTS `category`;
CREATE TABLE `category` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `type` int(11) NOT NULL COMMENT '分类类型 1:菜品分类，2:套餐分类',
  `name` varchar(32) NOT NULL COMMENT '分类名称',
  `sort` int(11) NOT NULL DEFAULT '0' COMMENT '顺序',
  `status` int(11) NOT NULL COMMENT '状态 0:禁用，1:启用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `create_user` bigint(20) NOT NULL COMMENT '创建人',
  `update_user` bigint(20) NOT NULL COMMENT '修改人',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分类';

-- 菜品表
DROP TABLE IF EXISTS `dish`;
CREATE TABLE `dish` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) NOT NULL COMMENT '菜品名称',
  `category_id` bigint(20) NOT NULL COMMENT '菜品分类 id',
  `price` decimal(10,2) DEFAULT NULL COMMENT '菜品价格',
  `image` varchar(255) DEFAULT NULL COMMENT '图片',
  `description` varchar(255) DEFAULT NULL COMMENT '描述信息',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '0 停售，1 起售',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `create_user` bigint(20) NOT NULL COMMENT '创建人',
  `update_user` bigint(20) NOT NULL COMMENT '修改人',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品';

-- 菜品口味表
DROP TABLE IF EXISTS `dish_flavor`;
CREATE TABLE `dish_flavor` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dish_id` bigint(20) NOT NULL COMMENT '菜品 id',
  `name` varchar(32) DEFAULT NULL COMMENT '口味名称',
  `value` varchar(255) DEFAULT NULL COMMENT '口味数据',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `create_user` bigint(20) NOT NULL COMMENT '创建人',
  `update_user` bigint(20) NOT NULL COMMENT '修改人',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品口味';

-- 套餐表
DROP TABLE IF EXISTS `setmeal`;
CREATE TABLE `setmeal` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `category_id` bigint(20) NOT NULL COMMENT '套餐分类 id',
  `name` varchar(32) NOT NULL COMMENT '套餐名称',
  `price` decimal(10,2) NOT NULL COMMENT '套餐价格',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '0 停售，1 起售',
  `description` varchar(255) DEFAULT NULL COMMENT '描述信息',
  `image` varchar(255) DEFAULT NULL COMMENT '图片',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `create_user` bigint(20) NOT NULL COMMENT '创建人',
  `update_user` bigint(20) NOT NULL COMMENT '修改人',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐';

-- 套餐菜品关系表
DROP TABLE IF EXISTS `setmeal_dish`;
CREATE TABLE `setmeal_dish` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `setmeal_id` bigint(20) NOT NULL COMMENT '套餐 id',
  `dish_id` bigint(20) NOT NULL COMMENT '菜品 id',
  `name` varchar(32) DEFAULT NULL COMMENT '菜品名称',
  `price` decimal(10,2) DEFAULT NULL COMMENT '菜品原价',
  `copies` int(11) NOT NULL COMMENT '份数',
  `sort` int(11) NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `create_user` bigint(20) NOT NULL COMMENT '创建人',
  `update_user` bigint(20) NOT NULL COMMENT '修改人',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐菜品关系';

-- 购物车表
DROP TABLE IF EXISTS `shopping_cart`;
CREATE TABLE `shopping_cart` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) DEFAULT NULL COMMENT '商品名称',
  `user_id` bigint(20) NOT NULL COMMENT '用户 id',
  `dish_id` bigint(20) DEFAULT NULL COMMENT '菜品 id',
  `setmeal_id` bigint(20) DEFAULT NULL COMMENT '套餐 id',
  `dish_flavor` varchar(50) DEFAULT NULL COMMENT '口味',
  `number` int(11) NOT NULL DEFAULT '1' COMMENT '数量',
  `amount` decimal(10,2) NOT NULL COMMENT '金额',
  `image` varchar(255) DEFAULT NULL COMMENT '图片',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='购物车';

-- 订单表
DROP TABLE IF EXISTS `orders`;
CREATE TABLE `orders` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `number` varchar(50) DEFAULT NULL COMMENT '订单号',
  `status` int(11) NOT NULL DEFAULT '1' COMMENT '订单状态 1 待付款 2 待接单 3 已接单 4 派送中 5 已完成 6 已取消',
  `user_id` bigint(20) NOT NULL COMMENT '下单用户',
  `address_book_id` bigint(20) NOT NULL COMMENT '地址 id',
  `order_time` datetime NOT NULL COMMENT '下单时间',
  `checkout_time` datetime DEFAULT NULL COMMENT '结账时间',
  `pay_method` int(11) NOT NULL DEFAULT '1' COMMENT '支付方式 1 微信',
  `pay_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '支付状态 0 未支付 1 已支付 2 退款',
  `amount` decimal(10,2) NOT NULL COMMENT '实收金额',
  `remark` varchar(100) DEFAULT NULL COMMENT '备注',
  `phone` varchar(11) DEFAULT NULL COMMENT '手机号',
  `address` varchar(255) DEFAULT NULL COMMENT '地址',
  `user_name` varchar(50) DEFAULT NULL COMMENT '用户名称',
  `consignee` varchar(50) DEFAULT NULL COMMENT '收货人',
  `cancel_reason` varchar(255) DEFAULT NULL COMMENT '订单取消原因',
  `rejection_reason` varchar(255) DEFAULT NULL COMMENT '订单拒绝原因',
  `cancel_time` datetime DEFAULT NULL COMMENT '订单取消时间',
  `estimated_delivery_time` datetime DEFAULT NULL COMMENT '预计送达时间',
  `delivery_status` tinyint(4) NOT NULL DEFAULT '0' COMMENT '配送状态 0 未配送 1 已配送',
  `delivery_time` datetime DEFAULT NULL COMMENT '配送时间',
  `pack_amount` int(11) DEFAULT NULL COMMENT '打包费',
  `tableware_number` int(11) DEFAULT NULL COMMENT '餐具数量',
  `tableware_status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '餐具数量状态 1 需要 0 不需要',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单';

-- 订单明细表
DROP TABLE IF EXISTS `order_detail`;
CREATE TABLE `order_detail` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name` varchar(32) DEFAULT NULL COMMENT '名字',
  `image` varchar(255) DEFAULT NULL COMMENT '图片',
  `order_id` bigint(20) NOT NULL COMMENT '订单 id',
  `dish_id` bigint(20) DEFAULT NULL COMMENT '菜品 id',
  `setmeal_id` bigint(20) DEFAULT NULL COMMENT '套餐 id',
  `dish_flavor` varchar(50) DEFAULT NULL COMMENT '口味',
  `number` int(11) NOT NULL DEFAULT '1' COMMENT '数量',
  `amount` decimal(10,2) NOT NULL COMMENT '金额',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细';

-- 地址表
DROP TABLE IF EXISTS `address_book`;
CREATE TABLE `address_book` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint(20) NOT NULL COMMENT '用户 id',
  `consignee` varchar(50) DEFAULT NULL COMMENT '收货人',
  `sex` varchar(2) DEFAULT NULL COMMENT '性别',
  `phone` varchar(11) NOT NULL COMMENT '手机号',
  `label` varchar(100) DEFAULT NULL COMMENT '标签',
  `is_default` tinyint(4) NOT NULL DEFAULT '0' COMMENT '默认 0 否 1 是',
  `province_code` varchar(12) DEFAULT NULL COMMENT '省',
  `province_name` varchar(32) DEFAULT NULL COMMENT '省',
  `city_code` varchar(12) DEFAULT NULL COMMENT '市',
  `city_name` varchar(32) DEFAULT NULL COMMENT '市',
  `district_code` varchar(12) DEFAULT NULL COMMENT '区',
  `district_name` varchar(32) DEFAULT NULL COMMENT '区',
  `detail` varchar(128) DEFAULT NULL COMMENT '详细地址',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地址';

-- ==================== Python Agent 售后协作（阶段 2 新增） ====================
-- 售后工单表：Python Agent 的 create_ticket 工具经 Java /internal/tickets 落库
DROP TABLE IF EXISTS `customer_service_ticket`;
CREATE TABLE `customer_service_ticket` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `ticket_no` varchar(32) NOT NULL COMMENT '工单号（业务唯一，TK + 随机值）',
  `user_id` bigint(20) NOT NULL COMMENT '提单人用户 id',
  `request_id` varchar(64) DEFAULT NULL COMMENT '用户发送动作的幂等键；旧数据可为空',
  `order_number` varchar(50) DEFAULT NULL COMMENT '关联订单号（可为空）',
  `category` varchar(32) NOT NULL COMMENT '工单类别：退款/催单/投诉/配送/其他',
  `content` varchar(500) NOT NULL COMMENT '用户诉求内容',
  `status` tinyint(4) NOT NULL DEFAULT '1' COMMENT '状态：1待处理 2处理中 3已完结',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ticket_no` (`ticket_no`),
  UNIQUE KEY `uk_ticket_user_request` (`user_id`, `request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='售后工单';
