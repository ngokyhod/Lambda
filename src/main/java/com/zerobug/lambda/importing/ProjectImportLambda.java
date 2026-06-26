package com.zerobug.lambda.importing;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.jgit.api.Git;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class ProjectImportLambda implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    // Khởi tạo S3 Client (Nên sử dụng Region giống với Bedrock của bạn là US_EAST_1)
    private final S3Client s3Client = S3Client.builder().region(Region.US_EAST_1).build();
    
    // Tên Bucket bạn sẽ tạo sau này, lấy từ biến môi trường của Lambda
    private final String TARGET_S3_BUCKET = System.getenv("SOURCE_CODE_BUCKET");

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> inputEvent, Context context) {
        context.getLogger().log("--- BẮT ĐẦU IMPORT PROJECT ---");

        String projectId = (String) inputEvent.get("projectId");
        String importType = (String) inputEvent.get("importType"); 
        String sourceUrl = (String) inputEvent.get("sourceUrl"); // Link Git hoặc Link File Zip

        Map<String, Object> output = new HashMap<>();
        output.put("projectId", projectId);

        try {
            // Đường dẫn lưu trữ tạm thời trên AWS Lambda (Tối đa 512MB)
            Path localTmpDir = Paths.get("/tmp/projects/" + projectId);
            Files.createDirectories(localTmpDir);

            // 1. XỬ LÝ THEO LOẠI IMPORT
            if ("GIT".equalsIgnoreCase(importType)) {
                context.getLogger().log("Tiến hành Clone Git từ: " + sourceUrl);
                cloneGitRepository(sourceUrl, localTmpDir.toFile());
                
            } else if ("ZIP".equalsIgnoreCase(importType)) {
                context.getLogger().log("Tiến hành tải và giải nén file ZIP từ: " + sourceUrl);
                downloadAndExtractZip(sourceUrl, localTmpDir.toFile(), context);
                
            } else {
                throw new IllegalArgumentException("Loại import không hợp lệ. Chỉ hỗ trợ GIT hoặc ZIP.");
            }

            // 2. TẢI TOÀN BỘ FILE ĐÃ XỬ LÝ LÊN AMAZON S3
            context.getLogger().log("Tiến hành đồng bộ file lên S3 Bucket...");
            String s3Prefix = "projects/" + projectId + "/source/";
            uploadDirectoryToS3(localTmpDir, s3Prefix, context);

            // Xóa file rác trong /tmp để giải phóng dung lượng cho Lambda
            deleteDirectory(localTmpDir.toFile());

            // 3. TRẢ KẾT QUẢ CHO STEP FUNCTIONS
            output.put("s3SourcePath", s3Prefix);
            output.put("status", "SUCCESS");
            context.getLogger().log("--- IMPORT THÀNH CÔNG ---");
            return output;

        } catch (Exception e) {
            context.getLogger().log("LỖI IMPORT: " + e.getMessage());
            output.put("status", "FAILED");
            output.put("errorMessage", e.getMessage());
            return output;
        }
    }

    /**
     * Hàm dùng JGit để clone mã nguồn
     */
    private void cloneGitRepository(String repoUrl, File destDir) throws Exception {
        try (Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(destDir)
                .setCloneAllBranches(false)
                .call()) {
            // Clone xong, xóa thư mục .git ẩn để tránh tốn dung lượng S3 không cần thiết
            File gitFolder = new File(destDir, ".git");
            if (gitFolder.exists()) {
                deleteDirectory(gitFolder);
            }
        }
    }

    /**
     * Hàm tải và giải nén file Zip
     */
    private void downloadAndExtractZip(String zipUrl, File destDir, Context context) throws Exception {
        // Lưu ý: Nếu zipUrl là link file từ giao diện Web ném xuống, 
        // bạn có thể dùng java.net.URL để tải về thành InputStream.
        // Ở đây giả lập việc đọc từ InputStream của link đó:
        java.net.URL url = new java.net.URL(zipUrl);
        
        try (InputStream in = url.openStream();
             ZipInputStream zis = new ZipInputStream(in)) {
             
            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = new File(destDir, zipEntry.getName());
                
                // Tránh lỗi bảo mật Zip Slip
                if (!newFile.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator)) {
                    throw new IOException("Zip entry bị lỗi bảo mật (Zip Slip): " + zipEntry.getName());
                }

                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    /**
     * Hàm duyệt cây thư mục local và đẩy tất cả lên Amazon S3
     */
    private void uploadDirectoryToS3(Path localDir, String s3Prefix, Context context) throws Exception {
        if (TARGET_S3_BUCKET == null || TARGET_S3_BUCKET.isEmpty()) {
            context.getLogger().log("CẢNH BÁO: Chưa cấu hình biến môi trường SOURCE_CODE_BUCKET. Bỏ qua bước đẩy lên S3.");
            return;
        }

        Files.walk(localDir).filter(Files::isRegularFile).forEach(path -> {
            try {
                // Tạo S3 Key (Đường dẫn trên S3) tương ứng với cấu trúc thư mục
                String relativePath = localDir.relativize(path).toString().replace("\\", "/");
                String s3Key = s3Prefix + relativePath;

                PutObjectRequest putObj = PutObjectRequest.builder()
                        .bucket(TARGET_S3_BUCKET)
                        .key(s3Key)
                        .build();

                s3Client.putObject(putObj, RequestBody.fromFile(path.toFile()));
                
            } catch (Exception e) {
                context.getLogger().log("Không thể upload file: " + path.getFileName() + " - " + e.getMessage());
            }
        });
    }

    /**
     * Hàm tiện ích: Xóa thư mục tạm
     */
    private void deleteDirectory(File directoryToBeDeleted) {
        File[] allContents = directoryToBeDeleted.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectory(file);
            }
        }
        directoryToBeDeleted.delete();
    }
}