-- ================================================================
-- 02-sample-data.sql —— Docker 一键部署的【演示种子数据】
-- 只在 MySQL 数据卷首次初始化时（01 建表脚本之后）自动执行一次；
-- 与生产无关，仅为让容器环境开箱可演示：后台可登录、Agent 能查到订单。
-- 重置方式：docker compose down -v 后重新 up，会重新生成。
-- ================================================================
-- mysql CLI 的会话字符集可能仍是 latin1，即使数据库/表默认是 utf8mb4。
-- 必须在任何中文 INSERT 前明确声明输入文件采用 UTF-8。
SET NAMES utf8mb4;
USE sky_take_out;

-- 管理员：admin / 123456（密码列为 MD5，与苍穹外卖 PasswordUtil.md5 一致）
INSERT IGNORE INTO `employee`
(`id`,`name`,`username`,`password`,`phone`,`sex`,`id_number`,`status`,`create_time`,`update_time`,`create_user`,`update_user`) VALUES
(1,'管理员','admin','e10adc3949ba59abbe56e057f20f883e','13800000001','男','11010119900101001X',1,NOW(),NOW(),1,1);

-- C 端用户（Python Agent 工具按 userId 查数据，固定 id=1 便于联调）
INSERT IGNORE INTO `user` (`id`,`openid`,`name`,`phone`,`sex`,`create_time`) VALUES
(1,'demo-openid-0001','测试用户','13800138000','男',NOW());

-- 分类
INSERT IGNORE INTO `category`
(`id`,`type`,`name`,`sort`,`status`,`create_time`,`update_time`,`create_user`,`update_user`) VALUES
(1,1,'热门川菜',99,1,NOW(),NOW(),1,1),
(2,1,'招牌套餐',100,0,NOW(),NOW(),1,1);

-- 菜品（price 单位：元）
INSERT IGNORE INTO `dish`
(`id`,`name`,`category_id`,`price`,`description`,`status`,`create_time`,`update_time`,`create_user`,`update_user`) VALUES
(1,'鱼香肉丝',1,26.00,'经典川菜，咸甜微辣，配饭一绝',1,NOW(),NOW(),1,1),
(2,'宫保鸡丁',1,22.00,'花生米与鸡腿丁的经典搭配',1,NOW(),NOW(),1,1);

-- 收货地址（orders.address_book_id 非空，必须有）
INSERT IGNORE INTO `address_book`
(`id`,`user_id`,`consignee`,`sex`,`phone`,`label`,`is_default`,`province_name`,`city_name`,`district_name`,`detail`) VALUES
(1,1,'张三','男','13800138000','公司',1,'北京市','北京市','朝阳区','望京 SOHO T1');

-- 订单：一张已完成、一张派送中（status 语义见建表注释；pay_status 1=已支付）
INSERT IGNORE INTO `orders`
(`id`,`number`,`status`,`user_id`,`address_book_id`,`order_time`,`checkout_time`,`pay_method`,`pay_status`,
 `amount`,`remark`,`phone`,`address`,`user_name`,`consignee`,`estimated_delivery_time`,`delivery_status`,`delivery_time`,`tableware_status`) VALUES
(1,'20260915120000001',5,1,1,'2026-09-15 12:00:00','2026-09-15 12:01:00',1,1,
 48.00,'少放辣','13800138000','北京市朝阳区望京 SOHO T1','测试用户','张三','2026-09-15 12:30:00',1,'2026-09-15 12:28:00',1),
(2,'20260917123000002',4,1,1,'2026-09-17 12:30:00',NULL,1,1,
 22.00,'多放花生','13800138000','北京市朝阳区望京 SOHO T1','测试用户','张三','2026-09-17 13:00:00',0,NULL,1);

-- 订单明细
INSERT IGNORE INTO `order_detail` (`id`,`order_id`,`dish_id`,`name`,`number`,`amount`) VALUES
(1,1,1,'鱼香肉丝',2,26.00),
(2,2,2,'宫保鸡丁',1,22.00);
