package com.zerobug.lambda.context;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

/**
 * Lambda 13 (Context Builder) - Đã nâng cấp sang Google Gemini Embedding
 * Nhiệm vụ: Biến câu hỏi thành Vector -> Tìm code trong RDS -> Gói thành Prompt
 */
public class RagContextLambda implements RequestHandler<Map<String, String>, Map<String, String>> {

    // Lấy thông tin Database và API Key từ biến môi trường của AWS
    private static final String DB_URL = System.getenv("DB_URL");
    private static final String DB_USER = System.getenv("DB_USER");
    private static final String DB_PASS = System.getenv("DB_PASS");
    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");

    @Override
    public Map<String, String> handleRequest(Map<String, String> input, Context context) {
        String userQuery = input.get("query");
        context.getLogger().log("1. RAG bắt đầu xử lý câu hỏi: " + userQuery);

        Map<String, String> output = new HashMap<>();

        try {
            if (GEMINI_API_KEY == null || GEMINI_API_KEY.isEmpty()) {
                throw new IllegalStateException("Hệ thống thiếu GEMINI_API_KEY trong Environment Variables!");
            }

            // ==========================================
            // BƯỚC 1: GỌI GEMINI NHÚNG CÂU HỎI THÀNH VECTOR (768 chiều)
            // ==========================================
            // 1. Đổi link URL sang model mới nhất: gemini-embedding-001
String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent?key=" + GEMINI_API_KEY;

// 2. Cấu hình JSON gọi model mới
JSONObject payload = new JSONObject();
payload.put("model", "models/gemini-embedding-001");

// THÊM DÒNG NÀY: Ép Google trả về đúng chuẩn 768 chiều để Database PostgreSQL không bị lỗi!
payload.put("outputDimensionality", 768);

JSONObject contentObj = new JSONObject();
contentObj.put("parts", new JSONArray().put(new JSONObject().put("text", userQuery)));
payload.put("content", contentObj);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Lỗi Google Embedding API: " + response.body());
            }

            // Bóc tách mảng Vector từ Google trả về
            JSONObject embedJson = new JSONObject(response.body());
            JSONArray vectorArray = embedJson.getJSONObject("embedding").getJSONArray("values");

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

            // Dùng JDBC thuần để tối ưu tốc độ Cold Start cho Lambda
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

            // Gửi qua cho Lambda 15 xử lý
            output.put("final_prompt", finalPrompt);
            output.put("status", "SUCCESS");

        } catch (Exception e) {
            context.getLogger().log("❌ LỖI RAG: " + e.getMessage());
            output.put("status", "ERROR");
            output.put("errorMessage", e.getMessage());
        }

        return output;
    }
}