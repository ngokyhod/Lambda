package com.zerobug.lambda.invoke;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class ApiInvokeLambda implements RequestHandler<Map<String, Object>, String> {

    // Lấy API Key từ cấu hình Environment Variables của AWS Lambda
    private final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");
    private final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=";

    @Override
    public String handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("🚀 Kích hoạt Lambda: Direct API Invoke (Google Gemini)");

        try {
            // Nhận prompt từ con Lambda RAG đứng trước nó truyền sang
            String prompt = (String) inputEvent.get("final_prompt");
            if (prompt == null || prompt.isEmpty()) {
                throw new IllegalArgumentException("Không tìm thấy 'final_prompt' trong payload.");
            }

            // Đóng gói JSON gửi sang Google
            JSONObject payload = new JSONObject();
            JSONObject contents = new JSONObject();
            JSONObject parts = new JSONObject();
            parts.put("text", prompt);
            contents.put("parts", new JSONArray().put(parts));
            payload.put("contents", new JSONArray().put(contents));

            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.3);
            generationConfig.put("maxOutputTokens", 2048);
            payload.put("generationConfig", generationConfig);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + GEMINI_API_KEY))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .timeout(Duration.ofMinutes(2))
                    .build();

            // Nhận kết quả từ Google AI Studio
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Google API báo lỗi: " + response.body());
            }

            JSONObject responseJson = new JSONObject(response.body());
            String unitTestCode = responseJson.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");

            context.getLogger().log("✅ Gọi API thành công! Gemini đã sinh xong code.");
            return unitTestCode;

        } catch (Exception e) {
            context.getLogger().log("❌ LỖI TẠI LAMBDA: " + e.getMessage());
            return "ERROR_API_INVOKE: " + e.getMessage();
        }
    }
}