package com.zerobug.lambda.context;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/**
 * Lambda 13 (Context Builder)
 * Nhiệm vụ: Biến câu hỏi thành Vector -> Tìm code trong RDS -> Gói thành Prompt
 */
public class RagContextLambda implements RequestHandler<Map<String, String>, Map<String, String>> {

    private final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.US_EAST_1)
            .build();

    // Lấy thông tin Database từ biến môi trường của AWS
    private static final String DB_URL = System.getenv("DB_URL");
    private static final String DB_USER = System.getenv("DB_USER");
    private static final String DB_PASS = System.getenv("DB_PASS");

    @Override
    public Map<String, String> handleRequest(Map<String, String> input, Context context) {
        String userQuery = input.get("query");
        context.getLogger().log("1. RAG bắt đầu xử lý câu hỏi: " + userQuery);

        Map<String, String> output = new HashMap<>();

        try {
            // ==========================================
            // BƯỚC 1: GỌI TITAN NHÚNG CÂU HỎI THÀNH VECTOR
            // ==========================================
            JSONObject payload = new JSONObject();
            payload.put("inputText", userQuery);
            payload.put("dimensions", 1024);
            payload.put("normalize", true);

            InvokeModelRequest embedRequest = InvokeModelRequest.builder()
                    .modelId("amazon.titan-embed-text-v2:0")
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(payload.toString()))
                    .build();

            InvokeModelResponse embedResponse = bedrockClient.invokeModel(embedRequest);
            JSONObject embedJson = new JSONObject(embedResponse.body().asUtf8String());
            JSONArray vectorArray = embedJson.getJSONArray("embedding");

            // Ép mảng JSON thành chuỗi "[0.1, 0.2, ...]" chuẩn của pgvector
            StringBuilder vectorString = new StringBuilder("[");
            for (int i = 0; i < vectorArray.length(); i++) {
                vectorString.append(vectorArray.getDouble(i));
                if (i < vectorArray.length() - 1) vectorString.append(",");
            }
            vectorString.append("]");

            // ==========================================
            // BƯỚC 2: TÌM CODE TRONG DATABASE (RETRIEVAL)
            // ==========================================
            context.getLogger().log("2. Kết nối RDS Database tìm code tương đồng...");
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("Đây là các đoạn mã nguồn (Context) trích xuất từ dự án:\n\n");

            // Dùng JDBC thuần thay cho Hibernate JPA để Lambda chạy nhanh nhất
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                String sql = "SELECT file_path, content FROM document_chunks ORDER BY embedding <=> ?::vector LIMIT 3";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, vectorString.toString());
                    try (ResultSet rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            contextBuilder.append("--- File: ").append(rs.getString("file_path")).append(" ---\n");
                            contextBuilder.append(rs.getString("content")).append("\n\n");
                        }
                    }
                }
            }

            // ==========================================
            // BƯỚC 3: GÓI PROMPT (AUGMENTATION)
            // ==========================================
            String finalPrompt = String.format(
                "Bạn là một chuyên gia kiểm thử phần mềm (QA/Tester) cấp cao Java. \n" +
                "%s\n" +
                "Dựa NHẤT QUÁN vào các đoạn mã nguồn trên, hãy thực hiện yêu cầu sau: %s\n" +
                "Chỉ trả về mã nguồn Unit Test, không cần giải thích dài dòng.", 
                contextBuilder.toString(), userQuery
            );

            // Gửi cục finalPrompt này đi. AWS sẽ tự động chuyền nó sang cho Lambda 15.
            output.put("final_prompt", finalPrompt);
            output.put("status", "SUCCESS");

        } catch (Exception e) {
            context.getLogger().log("LỖI RAG: " + e.getMessage());
            output.put("status", "ERROR");
            output.put("errorMessage", e.getMessage());
        }

        return output;
    }
}