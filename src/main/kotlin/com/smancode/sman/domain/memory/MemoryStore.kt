package com.smancode.sman.domain.memory

/**
 * 记忆存储接口
 *
 * 负责项目记忆的持久化和查询
 */
interface MemoryStore {

    /**
     * 保存记忆
     *
     * @param memory 要保存的记忆
     * @return Result.success 如果保存成功
     * @throws IllegalArgumentException 如果 memory 参数不合法
     */
    fun save(memory: ProjectMemory): Result<Unit>

    /**
     * 根据键加载记忆
     *
     * @param projectId 项目 ID
     * @param key 记忆键
     * @return Result.success(ProjectMemory?) 找到的记忆或 null
     */
    fun load(projectId: String, key: String): Result<ProjectMemory?>

    /**
     * 加载项目的所有记忆
     *
     * @param projectId 项目 ID
     * @return Result.success(List<ProjectMemory>) 所有记忆列表
     */
    fun loadAll(projectId: String): Result<List<ProjectMemory>>

    /**
     * 按类型加载记忆
     *
     * @param projectId 项目 ID
     * @param memoryType 记忆类型
     * @return Result.success(List<ProjectMemory>) 匹配的记忆列表
     */
    fun findByType(projectId: String, memoryType: MemoryType): Result<List<ProjectMemory>>

    /**
     * 删除记忆
     *
     * @param projectId 项目 ID
     * @param key 记忆键
     * @return Result.success 如果删除成功
     */
    fun delete(projectId: String, key: String): Result<Unit>

    /**
     * 更新记忆访问统计
     *
     * @param projectId 项目 ID
     * @param key 记忆键
     * @return Result.success 如果更新成功
     */
    fun touch(projectId: String, key: String): Result<Unit>
}
