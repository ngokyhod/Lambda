package com.zerobug.lambda.context;

import java.util.List;

/**
 * Entity đại diện cho một khối mã nguồn (Chunk) sau khi được cắt.
 * Cập nhật bổ sung Metadata và Dependency.
 */
public class DocumentChunk {
    private String filePath;
    private String packageName;
    private String className;
    private String methodName;
    private String methodSignature;
    private String content;
    private List<String> annotations;
    private List<String> dependencies;
    private List<String> constructors;
    private int startLine;
    private int endLine;

    public DocumentChunk() {}

    public DocumentChunk(String filePath, String packageName, String className, String methodName, 
                         String methodSignature, String content, List<String> annotations, 
                         List<String> dependencies, List<String> constructors, int startLine, int endLine) {
        this.filePath = filePath;
        this.packageName = packageName;
        this.className = className;
        this.methodName = methodName;
        this.methodSignature = methodSignature;
        this.content = content;
        this.annotations = annotations;
        this.dependencies = dependencies;
        this.constructors = constructors;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public static DocumentChunkBuilder builder() {
        return new DocumentChunkBuilder();
    }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    
    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }

    public String getMethodName() { return methodName; }
    public void setMethodName(String methodName) { this.methodName = methodName; }

    public String getMethodSignature() { return methodSignature; }
    public void setMethodSignature(String methodSignature) { this.methodSignature = methodSignature; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<String> getAnnotations() { return annotations; }
    public void setAnnotations(List<String> annotations) { this.annotations = annotations; }

    public List<String> getDependencies() { return dependencies; }
    public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }

    public List<String> getConstructors() { return constructors; }
    public void setConstructors(List<String> constructors) { this.constructors = constructors; }

    public int getStartLine() { return startLine; }
    public void setStartLine(int startLine) { this.startLine = startLine; }
    
    public int getEndLine() { return endLine; }
    public void setEndLine(int endLine) { this.endLine = endLine; }

    public static class DocumentChunkBuilder {
        private String filePath;
        private String packageName;
        private String className;
        private String methodName;
        private String methodSignature;
        private String content;
        private List<String> annotations;
        private List<String> dependencies;
        private List<String> constructors;
        private int startLine;
        private int endLine;

        public DocumentChunkBuilder filePath(String filePath) { this.filePath = filePath; return this; }
        public DocumentChunkBuilder packageName(String packageName) { this.packageName = packageName; return this; }
        public DocumentChunkBuilder className(String className) { this.className = className; return this; }
        public DocumentChunkBuilder methodName(String methodName) { this.methodName = methodName; return this; }
        public DocumentChunkBuilder methodSignature(String methodSignature) { this.methodSignature = methodSignature; return this; }
        public DocumentChunkBuilder content(String content) { this.content = content; return this; }
        public DocumentChunkBuilder annotations(List<String> annotations) { this.annotations = annotations; return this; }
        public DocumentChunkBuilder dependencies(List<String> dependencies) { this.dependencies = dependencies; return this; }
        public DocumentChunkBuilder constructors(List<String> constructors) { this.constructors = constructors; return this; }
        public DocumentChunkBuilder startLine(int startLine) { this.startLine = startLine; return this; }
        public DocumentChunkBuilder endLine(int endLine) { this.endLine = endLine; return this; }

        public DocumentChunk build() {
            return new DocumentChunk(filePath, packageName, className, methodName, methodSignature, 
                                     content, annotations, dependencies, constructors, startLine, endLine);
        }
    }
}
