-- H2 数据库常用查询脚本
-- 使用方法: ./h2-shell.sh < this_file.sql

-- ========================================
-- 1. 查看所有表
-- ========================================
SHOW TABLES;

-- ========================================
-- 2. 查看表结构
-- ========================================
-- DESCRIBE config;
-- DESCRIBE metadata;
-- DESCRIBE sop;
-- DESCRIBE vector_fragments;

-- ========================================
-- 3. 查询配置表
-- ========================================
-- SELECT * FROM config;
-- SELECT * FROM config WHERE key LIKE '%llm%';

-- ========================================
-- 4. 查询向量片段
-- ========================================
-- SELECT COUNT(*) as total FROM vector_fragments;

-- 查询冷数据统计
-- SELECT
--     cache_level,
--     COUNT(*) as count,
--     AVG(access_count) as avg_access
-- FROM vector_fragments
-- GROUP BY cache_level;

-- 查询最近访问的片段
-- SELECT
--     id,
--     title,
--     cache_level,
--     access_count,
--     last_accessed
-- FROM vector_fragments
-- ORDER BY last_accessed DESC
-- LIMIT 10;

-- ========================================
-- 5. 修改配置
-- ========================================
-- 插入或更新配置
-- MERGE INTO config (key, value, updated_at)
-- VALUES ('test.key', 'test value', CURRENT_TIMESTAMP);

-- 删除配置
-- DELETE FROM config WHERE key = 'test.key';

-- ========================================
-- 6. 修改向量片段
-- ========================================
-- 将某个片段提升为热数据
-- UPDATE vector_fragments
-- SET cache_level = 'hot',
--     last_accessed = CURRENT_TIMESTAMP
-- WHERE id = 'some_fragment_id';

-- 重置访问计数
-- UPDATE vector_fragments
-- SET access_count = 0
-- WHERE cache_level = 'cold';

-- ========================================
-- 7. 清理数据
-- ========================================
-- 清理 30 天未访问的冷数据
-- DELETE FROM vector_fragments
-- WHERE cache_level = 'cold'
--   AND last_accessed < DATEADD('DAY', -30, CURRENT_TIMESTAMP)
--   AND access_count < 5;

-- ========================================
-- 8. 备份和恢复
-- ========================================
-- 备份数据库（在命令行执行）
-- SCRIPT TO '/path/to/backup.sql';

-- 恢复数据库（在命令行执行）
-- RUNSCRIPT FROM '/path/to/backup.sql';
