package ai.smancode.sman.agent.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * BGE-M3 Embedding 客户端
 *
 * 功能：
 * 1. 调用 BGE-M3 模型生成文本向量
 * 2. 支持批量 embedding
 * 3. HTTP 通信
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
@Component
public class BgeM3EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(BgeM3EmbeddingClient.class);

    @Value("${vector.bge-m3.endpoint:http://localhost:8000}")
    private String endpoint;

    @Value("${vector.bge-m3.model-name:BAAI/bge-m3}")
    private String modelName;

    @Value("${vector.bge-m3.dimension:1024}")
    private int dimension;

    @Value("${vector.bge-m3.timeout:30000}")
    private int timeout;

    @Value("${vector.bge-m3.batch-size:10}")
    private int batchSize;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public BgeM3EmbeddingClient() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 生成单个文本的向量
     *
     * @param text 输入文本
     * @return 向量 (1024 维)
     */
    public float[] embedText(String text) {
        List<float[]> embeddings = embedTextBatch(List.of(text));
        return embeddings.isEmpty() ? new float[dimension] : embeddings.get(0);
    }

    /**
     * 批量生成文本向量
     *
     * @param texts 输入文本列表
     * @return 向量列表
     */
    public List<float[]> embedTextBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 构建请求体
            StringBuilder requestBody = new StringBuilder();
            requestBody.append("{\"model\":\"").append(modelName).append("\",\"input\":[");

            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) requestBody.append(",");
                // 转义 JSON 字符串
                String escaped = texts.get(i).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
                requestBody.append("\"").append(escaped).append("\"");
            }

            requestBody.append("]}");

            // 发送 HTTP 请求
            Request request = new Request.Builder()
                    .url(endpoint + "/v1/embeddings")
                    .post(RequestBody.create(requestBody.toString(), JSON))
                    .build();

            long startTime = System.currentTimeMillis();
            Response response = httpClient.newCall(request).execute();
            long duration = System.currentTimeMillis() - startTime;

            if (!response.isSuccessful()) {
                log.error("❌ BGE-M3 API 调用失败: HTTP {}, response={}", response.code(), response.body().string());
                return new ArrayList<>();
            }

            String responseBody = response.body().string();

            // 解析响应
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode dataNode = root.get("data");

            if (dataNode == null || !dataNode.isArray()) {
                log.error("❌ BGE-M3 API 响应格式错误: data 字段不存在或不是数组");
                return new ArrayList<>();
            }

            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : dataNode) {
                JsonNode embeddingNode = item.get("embedding");
                if (embeddingNode != null && embeddingNode.isArray()) {
                    float[] vector = new float[embeddingNode.size()];
                    for (int i = 0; i < embeddingNode.size(); i++) {
                        vector[i] = (float) embeddingNode.get(i).asDouble();
                    }
                    embeddings.add(vector);
                }
            }

            return embeddings;

        } catch (Exception e) {
            log.error("❌ BGE-M3 embedding 调用失败: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(endpoint + "/health")
                    .get()
                    .build();

            Response response = httpClient.newCall(request).execute();
            return response.isSuccessful();

        } catch (Exception e) {
            log.debug("BGE-M3 服务不可用: {}", e.getMessage());
            return false;
        }
    }
}
