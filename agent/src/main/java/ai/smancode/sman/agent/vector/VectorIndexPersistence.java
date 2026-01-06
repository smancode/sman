package ai.smancode.sman.agent.vector;

import ai.smancode.sman.agent.models.VectorModels.DocumentVector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 向量索引持久化服务
 *
 * 功能：
 * 1. 将内存中的向量索引保存到本地文件
 * 2. 支持 JVector 格式（.vec.bin + .docs.json + .graph.jvx）
 * 3. 支持元数据（meta.json）
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class VectorIndexPersistence {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexPersistence.class);

    @Value("${vector.index.path:data/vector-index}")
    private String vectorIndexPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 保存索引到本地文件
     *
     * @param projectKey 项目键
     * @param indexData 索引数据
     * @throws IOException 保存失败
     */
    public void saveIndex(String projectKey, VectorSearchService.JVectorIndexData indexData) throws IOException {
        Path projectDir = Path.of(vectorIndexPath, projectKey);
        Files.createDirectories(projectDir);

        log.info("💾 保存向量索引: projectKey={}, documents={}", projectKey, indexData.getDocuments().size());

        // 1. 保存元数据
        saveMetadata(projectDir, indexData);

        // 2. 保存文档数据 (JSON)
        saveDocuments(projectDir, indexData);

        // 3. 保存向量数据 (二进制)
        saveVectors(projectDir, indexData);

        log.info("✅ 向量索引保存完成: projectKey={}", projectKey);
    }

    /**
     * 保存元数据
     */
    private void saveMetadata(Path projectDir, VectorSearchService.JVectorIndexData indexData) throws IOException {
        Metadata meta = new Metadata();
        meta.lastBuiltAt = System.currentTimeMillis();
        meta.model = "BAAI/bge-m3";
        meta.vectorDim = indexData.getVectorDim();

        Path metaFile = projectDir.resolve("meta.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), meta);

        log.debug("保存元数据: {}", metaFile);
    }

    /**
     * 保存文档数据
     */
    private void saveDocuments(Path projectDir, VectorSearchService.JVectorIndexData indexData) throws IOException {
        Path docsFile = projectDir.resolve("class.docs.json");

        // 转换为 JSON 友好的格式
        List<?> docsList = indexData.getDocuments();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(docsFile.toFile(), docsList);

        log.debug("保存文档数据: {}, count={}", docsFile, docsList.size());
    }

    /**
     * 保存向量数据 (二进制)
     */
    private void saveVectors(Path projectDir, VectorSearchService.JVectorIndexData indexData) throws IOException {
        Path vecFile = projectDir.resolve("class.vec.bin");

        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(vecFile.toFile())))) {
            for (float[] vector : indexData.getVectors()) {
                for (float v : vector) {
                    dos.writeFloat(v);
                }
            }
        }

        log.debug("保存向量数据: {}, count={}", vecFile, indexData.getVectors().size());
    }

    /**
     * 元数据结构
     */
    private static class Metadata {
        public long lastBuiltAt;
        public String model;
        public int vectorDim;
    }
}
