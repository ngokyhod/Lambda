package com.zerobug.lambda.tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

public class FileTreeLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();
    private final String TARGET_S3_BUCKET = System.getenv("SOURCE_CODE_BUCKET");

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("--- BẮT ĐẦU QUÉT CÂY THƯ MỤC TỪ S3 ---");

        String projectId = (String) inputEvent.get("projectId");
        String s3SourcePath = (String) inputEvent.get("s3SourcePath"); 

        Map<String, Object> output = new HashMap<>();
        output.put("projectId", projectId);

        if (s3SourcePath == null || s3SourcePath.isEmpty()) {
            output.put("status", "FAILED");
            output.put("errorMessage", "Không tìm thấy đường dẫn S3 (s3SourcePath) để quét.");
            return output;
        }

        try {
            List<String> filePaths = new ArrayList<>();
            boolean isTruncated = true;
            String continuationToken = null;

            // Quét đệ quy toàn bộ file trong thư mục s3SourcePath
            while (isTruncated) {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(TARGET_S3_BUCKET)
                        .prefix(s3SourcePath);

                if (continuationToken != null) {
                    requestBuilder.continuationToken(continuationToken);
                }

                ListObjectsV2Response response = s3Client.listObjectsV2(requestBuilder.build());

                for (S3Object s3Object : response.contents()) {
                    if (!s3Object.key().equals(s3SourcePath)) {
                        String relativePath = s3Object.key().substring(s3SourcePath.length());
                        if (relativePath.startsWith("/")) {
                            relativePath = relativePath.substring(1);
                        }
                        filePaths.add(relativePath);
                    }
                }

                isTruncated = response.isTruncated();
                continuationToken = response.nextContinuationToken();
            }

            // ==========================================
            // THÊM MỚI: CHUYỂN ĐỔI FLAT LIST THÀNH NESTED TREE CHO FRONTEND
            // ==========================================
            List<Map<String, Object>> nestedTree = buildNestedTree(filePaths);

            output.put("fileTree", nestedTree);
            output.put("totalFiles", filePaths.size());
            output.put("status", "SUCCESS");
            
            context.getLogger().log("Đã quét và phân giải thành công " + filePaths.size() + " files.");
            return output;

        } catch (Exception e) {
            context.getLogger().log("LỖI QUÉT THƯ MỤC: " + e.getMessage());
            output.put("status", "FAILED");
            output.put("errorMessage", e.getMessage());
            return output;
        }
    }

    /**
     * Thuật toán phân giải danh sách đường dẫn phẳng thành cấu trúc Cây lồng nhau
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildNestedTree(List<String> paths) {
        List<Map<String, Object>> root = new ArrayList<>();
        Map<String, Map<String, Object>> folderMap = new HashMap<>();

        for (String path : paths) {
            String[] parts = path.split("/");
            String currentPath = "";
            List<Map<String, Object>> currentLevel = root;

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
                boolean isFile = (i == parts.length - 1);

                Map<String, Object> node = folderMap.get(currentPath);
                
                if (node == null) {
                    node = new HashMap<>();
                    node.put("name", part);
                    node.put("path", currentPath);
                    node.put("type", isFile ? "file" : "folder");
                    
                    if (isFile) {
                        // Xác định ngôn ngữ để trình soạn thảo Monaco tô màu cú pháp
                        if (part.endsWith(".java")) node.put("language", "java");
                        else if (part.endsWith(".xml")) node.put("language", "xml");
                        else if (part.endsWith(".json")) node.put("language", "json");
                        else node.put("language", "plaintext");
                    } else {
                        node.put("children", new ArrayList<Map<String, Object>>());
                    }
                    
                    currentLevel.add(node);
                    folderMap.put(currentPath, node);
                }

                if (!isFile) {
                    currentLevel = (List<Map<String, Object>>) node.get("children");
                }
            }
        }
        return root;
    }
}