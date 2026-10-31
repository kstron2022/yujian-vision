-- ================================================================================
-- M5-OpenAPI 运维反馈调优（2026-09-26）: open_app.rate_limit 默认值 10 → 50
--   频控意义在防滥用而非卡正常对接: 点播+轮询类第三方场景 10 QPS 过紧, 上调默认值;
--   存量应用数据不变（列 DEFAULT 只影响后续不指定该列的插入, 服务层兜底同步 OpenAppAuth.DEFAULT_RATE_LIMIT）
-- ================================================================================

ALTER TABLE open_app ALTER COLUMN rate_limit SET DEFAULT 50;

-- ---------------------------------------------------------------- 结果自检
SHOW COLUMNS FROM open_app LIKE 'rate_limit';

-- ---------------------------------------------------------------- 回滚(手动执行, Flyway 不跑注释)
-- ALTER TABLE open_app ALTER COLUMN rate_limit SET DEFAULT 10;
