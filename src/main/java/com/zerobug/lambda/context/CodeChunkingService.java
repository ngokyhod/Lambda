package com.zerobug.lambda.context;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CodeChunkingService {

    private static final Logger log = LoggerFactory.getLogger(CodeChunkingService.class);

    // Hàm Router: Điều hướng chiến lược cắt file
    public List<DocumentChunk> chunkFile(String filePath, String sourceCode) {
        if (filePath.endsWith(".java")) {
            return chunkJavaFile(filePath, sourceCode);
        } else {
            return chunkGenericTextFile(filePath, sourceCode);
        }
    }

    // Luồng 1: Xử lý chuyên sâu cho file Java (AST)
    private List<DocumentChunk> chunkJavaFile(String filePath, String sourceCode) {
        List<DocumentChunk> chunks = new ArrayList<>();
        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);
            
            // 1. Trích xuất Package
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // 2. Lấy toàn bộ danh sách Import
            String imports = cu.getImports().stream()
                    .map(i -> i.toString())
                    .collect(Collectors.joining(""));

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
                String className = classDecl.getNameAsString();
                
                // 3. Trích xuất Annotations của Class
                List<String> classAnnotations = classDecl.getAnnotations().stream()
                        .map(a -> a.toString())
                        .collect(Collectors.toList());

                // 4. Trích xuất Dependency (Các Fields của Class)
                List<String> dependencies = classDecl.getFields().stream()
                        .flatMap(field -> field.getVariables().stream())
                        .map(var -> var.getTypeAsString())
                        .collect(Collectors.toList());
                        
                String classFields = classDecl.getFields().stream()
                        .map(FieldDeclaration::toString)
                        .collect(Collectors.joining("\n"));

                // 5. Trích xuất Constructor
                List<String> constructors = classDecl.getConstructors().stream()
                        .map(c -> c.getDeclarationAsString(true, true, true))
                        .collect(Collectors.toList());

                // 6. Cắt từng Method (dùng getMethods() thay vì findAll để tránh inner class)
                classDecl.getMethods().forEach(methodDecl -> {
                    int startLine = methodDecl.getBegin().map(pos -> pos.line).orElse(0);
                    int endLine = methodDecl.getEnd().map(pos -> pos.line).orElse(0);

                    String javadoc = methodDecl.getJavadocComment().map(c -> c.getContent()).orElse("");
                    String methodCode = methodDecl.getTokenRange().map(r -> r.toString()).orElse(methodDecl.toString());
                    String methodSignature = methodDecl.getDeclarationAsString(true, true, true);
                    
                    // Gợi ý: Tối ưu hóa nội dung chunk để tập trung vào phương thức.
                    // Ngữ cảnh đầy đủ hơn có thể được tái tạo lúc cần thiết từ các metadata.
                    StringBuilder chunkContent = new StringBuilder();
                    chunkContent.append("// File: ").append(filePath).append("\n");
                    chunkContent.append("// Class: ").append(className).append("\n\n");
                    if (!javadoc.isBlank()) {
                        chunkContent.append("/**\n").append(javadoc).append("*/\n");
                    }
                    chunkContent.append(methodCode);
                    // Việc đưa toàn bộ class vào mỗi chunk có thể gây nhiễu.
                    // Chỉ cần nội dung method và metadata là đủ để RAG tìm kiếm.

                    chunks.add(DocumentChunk.builder()
                            .filePath(filePath)
                            .packageName(packageName)
                            .className(className)
                            .methodName(methodDecl.getNameAsString())
                            .methodSignature(methodSignature)
                            .content(chunkContent.toString())
                            .annotations(classAnnotations)
                            .dependencies(dependencies)
                            .constructors(constructors)
                            .startLine(startLine)
                            .endLine(endLine)
                            .build());
                });
            });
        } catch (Exception e) {
            log.error("Lỗi khi parse file {} bằng JavaParser: {}", filePath, e.getMessage());
            return chunkGenericTextFile(filePath, sourceCode);
        }
        return chunks;
    }

    // Luồng 2: Chunking hỗ trợ đa ngôn ngữ bằng phương pháp Recursive Text Splitting
    private List<DocumentChunk> chunkGenericTextFile(String filePath, String sourceCode) {
        List<DocumentChunk> chunks = new ArrayList<>();
        final int MAX_CHUNK_SIZE = 1000;

        // Tách code theo các mức độ: hàm/class (khoảng trống đôi), dòng mới, rồi mới đến ký tự
        String[] blocks = sourceCode.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String block : blocks) {
            // Nếu một block quá lớn, ta chia nhỏ nó theo dòng
            if (block.length() > MAX_CHUNK_SIZE) {
                String[] lines = block.split("\n");
                for (String line : lines) {
                    if (currentChunk.length() + line.length() > MAX_CHUNK_SIZE && currentChunk.length() > 0) {
                        addChunk(chunks, filePath, currentChunk.toString().trim());
                        currentChunk.setLength(0);
                    }
                    currentChunk.append(line).append("\n");
                }
            } else {
                if (currentChunk.length() + block.length() > MAX_CHUNK_SIZE && currentChunk.length() > 0) {
                    addChunk(chunks, filePath, currentChunk.toString().trim());
                    currentChunk.setLength(0);
                }
                currentChunk.append(block).append("\n\n");
            }
        }
        
        if (currentChunk.length() > 0) {
            addChunk(chunks, filePath, currentChunk.toString().trim());
        }
        
        return chunks;
    }

    private void addChunk(List<DocumentChunk> chunks, String filePath, String content) {
        if (!content.isEmpty()) {
            chunks.add(DocumentChunk.builder()
                    .filePath(filePath)
                    .className("GenericFile")
                    // Tránh để null, nên gán tên mặc định
                    .methodName("UnknownMethod") 
                    .content(content)
                    .startLine(0)
                    .endLine(0)
                    // BẮT BUỘC THÊM 3 DÒNG NÀY: Khởi tạo List rỗng để DB không bị văng lỗi Null
                    .annotations(new ArrayList<>())
                    .dependencies(new ArrayList<>())
                    .constructors(new ArrayList<>())
                    .build());
        }
    }
}