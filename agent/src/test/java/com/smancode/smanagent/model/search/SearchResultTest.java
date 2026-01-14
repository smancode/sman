package com.smancode.smanagent.model.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchResult 单元测试
 */
class SearchResultTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void testConstructor() {
        SearchResult result = new SearchResult();
        assertNotNull(result);
    }

    @Test
    void testConstructorWithParameters() {
        SearchResult result = new SearchResult("code", "id1", "TestClass", "测试类内容", 0.95);

        assertEquals("code", result.getType());
        assertEquals("id1", result.getId());
        assertEquals("TestClass", result.getTitle());
        assertEquals("测试类内容", result.getContent());
        assertEquals(0.95, result.getScore());
    }

    @Test
    void testConstructorWithMetadata() {
        SearchResult result = new SearchResult("knowledge", "id1", "知识标题", "知识内容", 0.88, "{\"projectKey\":\"test\"}");

        assertEquals("knowledge", result.getType());
        assertEquals("{\"projectKey\":\"test\"}", result.getMetadata());
    }

    @Test
    void testGettersAndSetters() {
        SearchResult result = new SearchResult();

        result.setType("code");
        result.setId("id1");
        result.setTitle("TestClass");
        result.setContent("测试类内容");
        result.setScore(0.95);
        result.setMetadata("{\"path\":\"test.java\"}");

        assertEquals("code", result.getType());
        assertEquals("id1", result.getId());
        assertEquals("TestClass", result.getTitle());
        assertEquals("测试类内容", result.getContent());
        assertEquals(0.95, result.getScore());
        assertEquals("{\"path\":\"test.java\"}", result.getMetadata());
    }

    @Test
    void testIsCodeResult() {
        SearchResult codeResult = new SearchResult();
        codeResult.setType("code");

        assertTrue(codeResult.isCodeResult());
        assertFalse(codeResult.isKnowledgeResult());
    }

    @Test
    void testIsKnowledgeResult() {
        SearchResult knowledgeResult = new SearchResult();
        knowledgeResult.setType("knowledge");

        assertTrue(knowledgeResult.isKnowledgeResult());
        assertFalse(knowledgeResult.isCodeResult());
    }

    @Test
    void testJsonSerialization() throws JsonProcessingException {
        SearchResult result = new SearchResult("code", "id1", "TestClass", "测试类内容", 0.95);

        String json = objectMapper.writeValueAsString(result);

        assertTrue(json.contains("\"type\":\"code\""));
        assertTrue(json.contains("\"id\":\"id1\""));
        assertTrue(json.contains("\"title\":\"TestClass\""));
    }

    @Test
    void testJsonDeserialization() throws JsonProcessingException {
        String json = "{\"type\":\"code\",\"id\":\"id1\",\"title\":\"TestClass\",\"content\":\"内容\",\"score\":0.95}";

        SearchResult result = objectMapper.readValue(json, SearchResult.class);

        assertEquals("code", result.getType());
        assertEquals("id1", result.getId());
        assertEquals("TestClass", result.getTitle());
        assertEquals(0.95, result.getScore());
    }
}
