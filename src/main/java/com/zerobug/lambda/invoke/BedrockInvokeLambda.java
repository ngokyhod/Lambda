package com.zerobug.lambda.invoke;

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
 * Lambda 15 (Invoke Service) - Đã chuyển sang dùng Google Gemma 3 12B IT
 */
public class BedrockInvokeLambda implements RequestHandler<Map<String, Object>, String> {

    private final BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
            .region(Region.US_EAST_1) 
            .build();

    @Override
    public String handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("🚀 Kích hoạt Lambda 15: Bedrock Invoke Service (Google Gemma 3 12B IT)");

        try {
            String prompt = (String) inputEvent.get("final_prompt");
            if (prompt == null || prompt.isEmpty()) {
                throw new IllegalArgumentException("Không tìm thấy 'final_prompt' trong payload.");
            }

            // 1. Đóng gói JSON theo đúng cấu trúc API của dòng Google Gemma trên Bedrock
            JSONObject payload = new JSONObject();
            payload.put("prompt", prompt);
            payload.put("max_tokens", 2048); // Đảm bảo đủ độ dài để sinh code Java hoàn chỉnh
            payload.put("temperature", 0.3); // Giảm xuống 0.3 để sinh code chuẩn xác, ít bị sáng tạo lỗi
            payload.put("top_p", 0.9);

            // 2. Tạo Request gửi lên Amazon Bedrock (Sử dụng Model ID của Gemma 3 12B IT)
            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId("google.gemma-3-12b-instruct-v1:0") // Đổi ID tại đây
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(payload.toString()))
                    .build();

            context.getLogger().log("Đang gọi Bedrock Gemma 3 12B IT...");

            // 3. Nhận kết quả và bóc tách dữ liệu
            InvokeModelResponse response = bedrockClient.invokeModel(request);
            JSONObject responseJson = new JSONObject(response.body().asUtf8String());
            
            // Dòng Gemma trả về kết quả thuần túy trong trường "generation"
            String unitTestCode = responseJson.getString("generation");

            context.getLogger().log("✅ Gọi Bedrock thành công! Gemma 3 12B IT đã sinh xong code.");
            return unitTestCode;

        } catch (Exception e) {
            context.getLogger().log("❌ LỖI NGHIÊM TRỌNG TẠI LAMBDA 15: " + e.getMessage());
            return "ERROR_BEDROCK_INVOKE: " + e.getMessage();
        }
    }
}