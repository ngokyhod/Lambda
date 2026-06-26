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

    // Khởi tạo S3 Client
    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();
    
    // Tên Bucket lấy từ biến môi trường
    private final String TARGET_S3_BUCKET = System.getenv("SOURCE_CODE_BUCKET");

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("--- BẮT ĐẦU QUÉT CÂY THƯ MỤC TỪ S3 ---");

        String projectId = (String) inputEvent.get("projectId");
        // Lấy đường dẫn thư mục code từ Output của con Lambda Import truyền sang
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
                    // Loại bỏ chính thư mục gốc (nếu S3 trả về node thư mục rỗng)
                    if (!s3Object.key().equals(s3SourcePath)) {
                        // Cắt bỏ phần prefix gốc để lấy đường dẫn tương đối (Ví dụ: src/main/java/Main.java)
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

            // TODO (Tùy chọn): Nếu Frontend của bạn yêu cầu cấu trúc JSON dạng cây lồng nhau (Nested JSON Tree), 
            // bạn có thể viết thêm một hàm duyệt vòng lặp để chuyển List<String> này thành dạng Tree Node.
            // Ở đây mình trả về dạng mảng các đường dẫn (Flat List), Frontend thường tự parse được rất dễ dàng.

            output.put("fileTree", filePaths);
            output.put("totalFiles", filePaths.size());
            output.put("status", "SUCCESS");
            
            context.getLogger().log("Đã quét thành công " + filePaths.size() + " files.");
            return output;

        } catch (Exception e) {
            context.getLogger().log("LỖI QUÉT THƯ MỤC: " + e.getMessage());
            output.put("status", "FAILED");
            output.put("errorMessage", e.getMessage());
            return output;
        }
    }
}