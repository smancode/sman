package ai.smancode.sman.agent.models;

import java.util.List;
import java.util.Map;

/**
 * 调用链分析数据模型
 */
public class CallChainModels {

    /**
     * 调用链请求
     */
    public static class CallChainRequest {
        private String method;
        private String direction;
        private int depth;
        private boolean includeSource;
        private String projectKey;

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }

        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }

        public boolean isIncludeSource() { return includeSource; }
        public void setIncludeSource(boolean includeSource) { this.includeSource = includeSource; }

        public String getProjectKey() { return projectKey; }
        public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    }

    /**
     * 调用链结果
     */
    public static class CallChainResult {
        private boolean success;
        private String result;
        private String formattedResult;
        private String error;

        public CallChainResult() {}

        public static CallChainResult success(String result) {
            CallChainResult r = new CallChainResult();
            r.success = true;
            r.result = result;
            return r;
        }

        public static CallChainResult failure(String error) {
            CallChainResult r = new CallChainResult();
            r.success = false;
            r.error = error;
            return r;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        public String getFormattedResult() { return formattedResult; }
        public void setFormattedResult(String formattedResult) { this.formattedResult = formattedResult; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }

    /**
     * 调用链节点
     */
    public static class CallChainNode {
        private String className;
        private String methodName;
        private String relativePath;
        private int lineNum;
        private List<CallChainNode> children;

        public CallChainNode() {}

        public CallChainNode(String className, String methodName, String relativePath, int lineNum, List<CallChainNode> children) {
            this.className = className;
            this.methodName = methodName;
            this.relativePath = relativePath;
            this.lineNum = lineNum;
            this.children = children;
        }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

        public int getLineNum() { return lineNum; }
        public void setLineNum(int lineNum) { this.lineNum = lineNum; }

        public List<CallChainNode> getChildren() { return children; }
        public void setChildren(List<CallChainNode> children) { this.children = children; }
    }

    /**
     * 调用链入口点
     */
    public static class CallChainEntryPoint {
        private String className;
        private String methodName;
        private String relativePath;
        private String sourceCode;

        public CallChainEntryPoint() {}

        public CallChainEntryPoint(String className, String methodName, String relativePath, String sourceCode) {
            this.className = className;
            this.methodName = methodName;
            this.relativePath = relativePath;
            this.sourceCode = sourceCode;
        }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }

        public String getRelativePath() { return relativePath; }
        public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

        public String getSourceCode() { return sourceCode; }
        public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }
    }

    /**
     * 方法签名
     */
    public static class MethodSignature {
        private String className;
        private String methodName;

        public MethodSignature() {}

        public MethodSignature(String className, String methodName) {
            this.className = className;
            this.methodName = methodName;
        }

        public String getClassName() { return className; }
        public void setClassName(String className) { this.className = className; }

        public String getMethodName() { return methodName; }
        public void setMethodName(String methodName) { this.methodName = methodName; }
    }

    /**
     * 调用链分析选项
     */
    public static class CallChainAnalysisOptions {
        private String direction;
        private int depth;
        private boolean includeSource;
        private boolean includeSubclasses;

        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }

        public int getDepth() { return depth; }
        public void setDepth(int depth) { this.depth = depth; }

        public boolean isIncludeSource() { return includeSource; }
        public void setIncludeSource(boolean includeSource) { this.includeSource = includeSource; }

        public boolean isIncludeSubclasses() { return includeSubclasses; }
        public void setIncludeSubclasses(boolean includeSubclasses) { this.includeSubclasses = includeSubclasses; }
    }

    /**
     * 调用链统计信息
     */
    public static class CallChainStatistics {
        private int directCallersCount;
        private int directCalleesCount;
        private int totalCallersCount;
        private int totalCalleesCount;

        public int getDirectCallersCount() { return directCallersCount; }
        public void setDirectCallersCount(int directCallersCount) { this.directCallersCount = directCallersCount; }

        public int getDirectCalleesCount() { return directCalleesCount; }
        public void setDirectCalleesCount(int directCalleesCount) { this.directCalleesCount = directCalleesCount; }

        public int getTotalCallersCount() { return totalCallersCount; }
        public void setTotalCallersCount(int totalCallersCount) { this.totalCallersCount = totalCallersCount; }

        public int getTotalCalleesCount() { return totalCalleesCount; }
        public void setTotalCalleesCount(int totalCalleesCount) { this.totalCalleesCount = totalCalleesCount; }
    }
}
