-- 仅对本项目已有演示卷执行；新空卷会从 sky-take-out.sql 直接建成新表结构。
-- 幂等迁移：历史工单 request_id 为空，MySQL 唯一索引允许多个 NULL。
USE sky_take_out;

SELECT IF(COUNT(*) = 0,
  'ALTER TABLE customer_service_ticket ADD COLUMN request_id varchar(64) DEFAULT NULL COMMENT ''用户发送动作的幂等键'' AFTER user_id',
  'SELECT ''request_id column already exists''') INTO @ticket_column_sql
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_service_ticket' AND COLUMN_NAME = 'request_id';
PREPARE ticket_column_stmt FROM @ticket_column_sql;
EXECUTE ticket_column_stmt;
DEALLOCATE PREPARE ticket_column_stmt;

SELECT IF(COUNT(*) = 0,
  'ALTER TABLE customer_service_ticket ADD UNIQUE KEY uk_ticket_user_request (user_id, request_id)',
  'SELECT ''uk_ticket_user_request index already exists''') INTO @ticket_index_sql
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_service_ticket' AND INDEX_NAME = 'uk_ticket_user_request';
PREPARE ticket_index_stmt FROM @ticket_index_sql;
EXECUTE ticket_index_stmt;
DEALLOCATE PREPARE ticket_index_stmt;
