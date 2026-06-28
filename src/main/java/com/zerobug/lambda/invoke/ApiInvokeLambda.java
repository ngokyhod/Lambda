package com.zerobug.lambda.invoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class ApiInvokeLambda implements RequestHandler<Map<String, Object>, String> {

    private final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");
    private final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    @Override
    public String handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("🚀 Kích hoạt Lambda: Direct API Invoke (Google Gemini 2.5 Flash)");

        try {
            String prompt = (String) inputEvent.get("final_prompt");
            if (prompt == null || prompt.isEmpty()) {
                throw new IllegalArgumentException("Không tìm thấy 'final_prompt' trong payload.");
            }

            // ==========================================
            // TIÊM LỆNH ÉP BUỘC AI PHẢI NGOAN NGOÃN
            // ==========================================
            prompt += "\n\n[LƯU Ý RẤT QUAN TRỌNG]: Bạn chỉ được phép trả về duy nhất mã nguồn của Test Class. " +
                      "Tuyệt đối KHÔNG giải thích. KHÔNG xin lỗi. KHÔNG dùng các từ khóa như 'lỗi', 'error', 'fix' trong code comment. " +
                      "KHÔNG tự ý tạo thêm các class Entity (như User, Project...) vào chung file. Phải bắt đầu bằng chữ 'package'.";

            JSONObject payload = new JSONObject();
            payload.put("model", "models/gemini-2.5-flash");
            
            JSONObject contents = new JSONObject();
            JSONObject parts = new JSONObject();
            parts.put("text", prompt);
            contents.put("parts", new JSONArray().put(parts));
            payload.put("contents", new JSONArray().put(contents));

            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.2); // Hạ độ sáng tạo xuống để code chuẩn Form hơn
            generationConfig.put("maxOutputTokens", 4096);
            payload.put("generationConfig", generationConfig);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + GEMINI_API_KEY))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .timeout(Duration.ofMinutes(2))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Google API báo lỗi: " + response.body());
            }

            JSONObject responseJson = new JSONObject(response.body());
            String unitTestCode = responseJson.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");

            // ==========================================
            // BỘ LỌC REGEX XUYÊN GIÁP (Bất chấp AI nói nhảm)
            // ==========================================
            Pattern pattern = Pattern.compile("```(?:java)?\\s*([\\s\\S]*?)\\s*```");
            Matcher matcher = pattern.matcher(unitTestCode);
            
            if (matcher.find()) {
                // Nếu AI dùng Markdown, chỉ móc đúng phần ruột Code ra
                unitTestCode = matcher.group(1).trim();
            } else {
                // Nếu AI không dùng Markdown, cắt bỏ mọi câu hội thoại ở đầu
                unitTestCode = unitTestCode.trim();
                int packageIndex = unitTestCode.indexOf("package ");
                int importIndex = unitTestCode.indexOf("import ");
                
                int startIndex = -1;
                if (packageIndex != -1) startIndex = packageIndex;
                else if (importIndex != -1) startIndex = importIndex;
                
                if (startIndex != -1) {
                    unitTestCode = unitTestCode.substring(startIndex);
                }
            }

            context.getLogger().log("✅ API thành công! Đã trích xuất mã nguồn tinh khiết 100%.");
            return unitTestCode;

        } catch (Exception e) {
            context.getLogger().log("❌ LỖI TẠI LAMBDA: " + e.getMessage());
            return "ERROR_API_INVOKE: " + e.getMessage();
        }
    }
}