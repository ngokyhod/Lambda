package com.zerobug.lambda.context;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
public class RagContextLambda implements RequestHandler<Map<String, String>, Map<String, String>> {

    // Khởi tạo S3 Client
    private final S3Client s3Client = S3Client.builder().build();
    // Tên Bucket S3 của bạn (Có thể truyền qua Environment Variable)
    private static final String S3_BUCKET_NAME = System.getenv("S3_BUCKET_NAME");

    @Override
    public Map<String, String> handleRequest(Map<String, String> input, Context context) {
        String userQuery = input.get("query");
        Map<String, String> output = new HashMap<>();

        try {
            // 1. Lấy thông tin file cần test (Giả sử Lamba trước truyền vào tên file)
            // Hoặc bạn có thể code logic liệt kê file từ S3 ở đây
            String targetFileName = input.getOrDefault("file_path", "Demo.java"); // Code cứng tạm để test

            // 2. Tải nội dung file từ S3
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(S3_BUCKET_NAME)
                    .key(targetFileName)
                    .build();
            
            ResponseBytes<GetObjectResponse> objectBytes = s3Client.getObjectAsBytes(getObjectRequest);
            String sourceCode = new String(objectBytes.asByteArray(), StandardCharsets.UTF_8);

            // 3. Xây dựng Prompt
            StringBuilder contextBuilder = new StringBuilder();
            contextBuilder.append("Đây là mã nguồn cần kiểm thử:\n");
            contextBuilder.append("--- File: ").append(targetFileName).append(" ---\n");
            contextBuilder.append(sourceCode).append("\n\n");

            String finalPrompt = String.format(
                "Bạn là một chuyên gia QA Java. \n%s\nYêu cầu: %s\nChỉ trả về mã Unit Test.", 
                contextBuilder.toString(), userQuery
            );

            output.put("final_prompt", finalPrompt);
            output.put("status", "SUCCESS");

        } catch (Exception e) {
            context.getLogger().log("❌ LỖI ĐỌC S3: " + e.getMessage());
            output.put("status", "ERROR");
            output.put("errorMessage", e.getMessage());
        }
        return output;
    }
}