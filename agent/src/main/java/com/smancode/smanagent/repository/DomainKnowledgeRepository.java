package com.smancode.smanagent.repository;

import com.smancode.smanagent.model.DomainKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * 领域知识 Repository
 * <p>
 * 使用 JdbcTemplate 实现 CRUD 操作。
 */
@Repository
public class DomainKnowledgeRepository {

    private static final Logger logger = LoggerFactory.getLogger(DomainKnowledgeRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public DomainKnowledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        initTable();
    }

    /**
     * 初始化表
     */
    private void initTable() {
        String sql = """
            CREATE TABLE IF NOT EXISTS domain_knowledge (
                id VARCHAR(36) PRIMARY KEY,
                project_key VARCHAR(100) NOT NULL,
                title VARCHAR(500) NOT NULL,
                content VARCHAR(4000) NOT NULL,
                embedding VARCHAR(4000),
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
            """;
        jdbcTemplate.execute(sql);
        logger.info("✅ 初始化 domain_knowledge 表");
    }

    /**
     * RowMapper
     */
    private static final class DomainKnowledgeRowMapper implements RowMapper<DomainKnowledge> {
        @Override
        public DomainKnowledge mapRow(ResultSet rs, int rowNum) throws SQLException {
            DomainKnowledge dk = new DomainKnowledge();
            dk.setId(rs.getString("id"));
            dk.setProjectKey(rs.getString("project_key"));
            dk.setTitle(rs.getString("title"));
            dk.setContent(rs.getString("content"));
            dk.setEmbedding(rs.getString("embedding"));
            dk.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            dk.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
            return dk;
        }
    }

    /**
     * 保存或更新
     */
    public DomainKnowledge save(DomainKnowledge domainKnowledge) {
        if (domainKnowledge.getId() == null || domainKnowledge.getId().isEmpty()) {
            throw new IllegalArgumentException("id 不能为空");
        }

        // 检查是否存在
        DomainKnowledge existing = findById(domainKnowledge.getId());
        if (existing != null) {
            // 更新
            String sql = """
                UPDATE domain_knowledge
                SET project_key = ?, title = ?, content = ?, embedding = ?, updated_at = ?
                WHERE id = ?
                """;
            domainKnowledge.touch();
            jdbcTemplate.update(sql,
                domainKnowledge.getProjectKey(),
                domainKnowledge.getTitle(),
                domainKnowledge.getContent(),
                domainKnowledge.getEmbedding(),
                domainKnowledge.getUpdatedAt(),
                domainKnowledge.getId()
            );
            logger.debug("更新领域知识: id={}", domainKnowledge.getId());
        } else {
            // 插入
            String sql = """
                INSERT INTO domain_knowledge (id, project_key, title, content, embedding, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
            jdbcTemplate.update(sql,
                domainKnowledge.getId(),
                domainKnowledge.getProjectKey(),
                domainKnowledge.getTitle(),
                domainKnowledge.getContent(),
                domainKnowledge.getEmbedding(),
                domainKnowledge.getCreatedAt(),
                domainKnowledge.getUpdatedAt()
            );
            logger.debug("保存领域知识: id={}", domainKnowledge.getId());
        }
        return domainKnowledge;
    }

    /**
     * 根据 ID 查询
     */
    public DomainKnowledge findById(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id 不能为空");
        }

        String sql = "SELECT * FROM domain_knowledge WHERE id = ?";
        List<DomainKnowledge> results = jdbcTemplate.query(sql, new DomainKnowledgeRowMapper(), id);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 根据项目标识查询所有
     */
    public List<DomainKnowledge> findByProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey 不能为空");
        }

        String sql = "SELECT * FROM domain_knowledge WHERE project_key = ? ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, new DomainKnowledgeRowMapper(), projectKey);
    }

    /**
     * 根据项目标识和标题查询
     */
    public DomainKnowledge findByProjectKeyAndTitle(String projectKey, String title) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey 不能为空");
        }
        if (title == null || title.isEmpty()) {
            throw new IllegalArgumentException("title 不能为空");
        }

        String sql = "SELECT * FROM domain_knowledge WHERE project_key = ? AND title = ?";
        List<DomainKnowledge> results = jdbcTemplate.query(sql, new DomainKnowledgeRowMapper(), projectKey, title);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 删除
     */
    public void deleteById(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id 不能为空");
        }

        String sql = "DELETE FROM domain_knowledge WHERE id = ?";
        int rows = jdbcTemplate.update(sql, id);
        logger.debug("删除领域知识: id={}, rows={}", id, rows);
    }

    /**
     * 根据项目标识删除所有
     */
    public void deleteByProjectKey(String projectKey) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey 不能为空");
        }

        String sql = "DELETE FROM domain_knowledge WHERE project_key = ?";
        int rows = jdbcTemplate.update(sql, projectKey);
        logger.debug("删除项目所有领域知识: projectKey={}, rows={}", projectKey, rows);
    }

    /**
     * 检查是否存在向量
     */
    public long countByProjectKeyAndEmbeddingIsNotNull(String projectKey) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey 不能为空");
        }

        String sql = "SELECT COUNT(*) FROM domain_knowledge WHERE project_key = ? AND embedding IS NOT NULL";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, projectKey);
        return count != null ? count : 0;
    }

    /**
     * 获取所有有向量的领域知识（用于向量搜索）
     */
    public List<DomainKnowledge> findAllWithEmbedding(String projectKey) {
        if (projectKey == null || projectKey.isEmpty()) {
            throw new IllegalArgumentException("projectKey 不能为空");
        }

        String sql = "SELECT * FROM domain_knowledge WHERE project_key = ? AND embedding IS NOT NULL";
        return jdbcTemplate.query(sql, new DomainKnowledgeRowMapper(), projectKey);
    }
}
