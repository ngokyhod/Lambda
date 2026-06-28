package com.zerobug.lambda.result;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class ResultAndHistoryLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();
    private final String TARGET_S3_BUCKET = System.getenv("SOURCE_CODE_BUCKET");
    
    // THÊM BIẾN MÔI TRƯỜNG DATABASE
    private final String DB_URL = System.getenv("DB_URL");
    private final String DB_USER = System.getenv("DB_USER");
    private final String DB_PASS = System.getenv("DB_PASS");

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("--- BẮT ĐẦU XỬ LÝ KẾT QUẢ VÀ LƯU LỊCH SỬ ---");

        // 1. Lấy dữ liệu từ các bước trước trong Step Functions truyền sang
        String projectId = (String) inputEvent.get("projectId");
        String userPrompt = (String) inputEvent.get("prompt"); // Câu hỏi trực tiếp từ Frontend
        String rawAiResponse = (String) inputEvent.get("rawAiResponse"); // Code thô AI sinh ra
        String modelId = (String) inputEvent.get("modelId");
        String requestedFile = (String) inputEvent.get("selectedFile");

        Map<String, Object> output = new HashMap<>();
        output.put("projectId", projectId);

        try {
            // 2. Dọn dẹp mã nguồn (Lọc bỏ các câu chào hỏi thừa của AI)
            String cleanCode = extractCodeFromMarkdown(rawAiResponse);

            // 3. Lưu file kết quả sạch lên S3 Bucket để Frontend có thể tải về (dự phòng)
            String resultFileId = UUID.randomUUID().toString();
            String s3ResultPath = "projects/" + projectId + "/results/test_" + resultFileId + ".java";

            if (TARGET_S3_BUCKET != null && !TARGET_S3_BUCKET.isEmpty()) {
                PutObjectRequest putObj = PutObjectRequest.builder()
                        .bucket(TARGET_S3_BUCKET)
                        .key(s3ResultPath)
                        .build();
                s3Client.putObject(putObj, RequestBody.fromString(cleanCode, StandardCharsets.UTF_8));
                context.getLogger().log("Đã lưu kết quả Unit Test lên S3: " + s3ResultPath);
            }

            // ==========================================
            // 4. LƯU LỊCH SỬ TRỰC TIẾP VÀO RDS POSTGRESQL
            // ==========================================
            context.getLogger().log("Tiến hành lưu History vào RDS PostgreSQL...");
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                // LƯU Ý: Đổi tên bảng 'generation_records' cho khớp với Schema thực tế trong DB của bạn
                String sql = "INSERT INTO generation_records (id, project_id, prompt, clean_code, target_file, model_used, created_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, resultFileId);
                    // Nếu project_id trong DB của bạn là kiểu số (Integer/Long), hãy dùng Integer.parseInt(projectId)
                    pstmt.setString(2, projectId); 
                    pstmt.setString(3, userPrompt);
                    pstmt.setString(4, cleanCode);
                    pstmt.setString(5, requestedFile);
                    pstmt.setString(6, modelId);
                    pstmt.executeUpdate();
                }
                context.getLogger().log("Ghi Lịch sử vào DB thành công!");
            } catch (Exception dbEx) {
                context.getLogger().log("Lỗi khi ghi RDS History: " + dbEx.getMessage());
                throw dbEx; // Báo lỗi cho Step Functions nếu rớt DB
            }
            // ==========================================

            // 5. Tổng hợp gói dữ liệu trả về cho Step Functions
            output.put("testFileUrl", s3ResultPath);
            output.put("cleanCode", cleanCode);
            output.put("userPrompt", userPrompt);
            output.put("modelUsed", modelId);
            output.put("targetFile", requestedFile);
            output.put("timestamp", Instant.now().toString());
            output.put("status", "SUCCESS");

            context.getLogger().log("--- HOÀN TẤT XỬ LÝ KẾT QUẢ ---");
            return output;

        } catch (Exception e) {
            context.getLogger().log("LỖI XỬ LÝ KẾT QUẢ: " + e.getMessage());
            output.put("status", "FAILED");
            output.put("errorMessage", e.getMessage());
            return output;
        }
    }

    /**
     * Hàm tiện ích: Trích xuất code từ Markdown block (```java ... ```)
     */
    private String extractCodeFromMarkdown(String rawText) {
        if (rawText == null) return "";
        
        int startIndex = rawText.indexOf("```");
        if (startIndex != -1) {
            // Tìm vị trí xuống dòng sau dấu ``` (ví dụ ```java\n)
            int firstNewline = rawText.indexOf('\n', startIndex);
            int endIndex = rawText.lastIndexOf("```");
            
            if (firstNewline != -1 && endIndex > firstNewline) {
                return rawText.substring(firstNewline + 1, endIndex).trim();
            }
        }
        // Nếu AI không dùng markdown, trả về nguyên bản
        return rawText.trim();
    }
}