package com.zerobug.lambda.invoke;

import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

/**
 * Lambda 15 (Invoke Service) - Nằm trong Data & AI Services
 * Nhiệm vụ: Giao tiếp độc quyền với Amazon Bedrock (Claude 3 Haiku)
 */
public class BedrockInvokeLambda implements RequestHandler<Map<String, Object>, String> {

    // Khởi tạo Client bên ngoài hàm handleRequest để giảm thời gian Cold Start
    private final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.US_EAST_1) 
            .build();

    @Override
    public String handleRequest(Map<String, Object> inputEvent, Context context) {
        // AWS Lambda tự động truyền "Context" vào để ghi Log lên CloudWatch
        context.getLogger().log("🚀 Kích hoạt Lambda 15: Bedrock Invoke Service");

        try {
            // 1. Lấy dữ liệu đầu vào (Được truyền từ Lambda 13 - Context Builder)
            // Giả định inputEvent có chứa key "final_prompt"
            String prompt = (String) inputEvent.get("final_prompt");
            if (prompt == null || prompt.isEmpty()) {
                throw new IllegalArgumentException("Không tìm thấy 'final_prompt' trong payload truyền vào.");
            }

            context.getLogger().log("Đã nhận Prompt độ dài: " + prompt.length() + " ký tự.");

            // 2. Đóng gói JSON theo chuẩn giao tiếp của Anthropic Claude 3
            JSONObject payload = new JSONObject();
            payload.put("anthropic_version", "bedrock-2023-05-31");
            payload.put("max_tokens", 3000); 
            
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);
            payload.put("messages", List.of(message));

            // 3. Tạo Request gửi lên Amazon Bedrock
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId("anthropic.claude-3-haiku-20240307-v1:0")
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(payload.toString()))
                    .build();

            context.getLogger().log("Đang gọi Bedrock Claude 3 Haiku...");

            // 4. Nhận kết quả và bóc tách
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            JSONObject responseJson = new JSONObject(response.body().asUtf8String());
            
            // Claude 3 trả code về trong mảng content[0].text
            String unitTestCode = responseJson.getJSONArray("content").getJSONObject(0).getString("text");

            context.getLogger().log("✅ Gọi Bedrock thành công! Đã sinh xong Unit Test.");
            
            // 5. Trả kết quả về cho hệ thống (để Lambda 19 Result Service xử lý tiếp)
            return unitTestCode;

        } catch (Exception e) {
            context.getLogger().log("❌ LỖI NGHIÊM TRỌNG TẠI LAMBDA 15: " + e.getMessage());
            // Trả về chuỗi báo lỗi để hệ thống Agentic biết mà xử lý
            return "ERROR_BEDROCK_INVOKE: " + e.getMessage();
        }
    }
}