package com.sky.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 阿里云 OSS 工具类
 */
@Component
@Slf4j
public class AliOssUtil {

    @Value("${sky.alioss.endpoint}")
    private String endpoint;

    @Value("${sky.alioss.access-key-id}")
    private String accessKeyId;

    @Value("${sky.alioss.access-key-secret}")
    private String accessKeySecret;

    @Value("${sky.alioss.bucket-name}")
    private String bucketName;

    /**
     * 上传文件到 OSS
     * @param inputStream 文件输入流
     * @param objectName 对象名称（包含路径）
     * @return 访问 URL
     */
    public String upload(InputStream inputStream, String objectName) {
        OSS ossClient = createOSSClient();
        try {
            ossClient.putObject(bucketName, objectName, inputStream);
            return getFileUrl(objectName);
        } catch (Exception e) {
            log.error("OSS 文件上传失败：{}", e.getMessage());
            throw new RuntimeException("OSS 文件上传失败", e);
        } finally {
            if (ossClient != null) {
                ossClient.shutdown();
            }
        }
    }

    /**
     * 上传字节数组到 OSS
     * @param bytes 文件字节数组
     * @param objectName 对象名称（包含路径）
     * @return 访问 URL
     */
    public String upload(byte[] bytes, String objectName) {
        return upload(new ByteArrayInputStream(bytes), objectName);
    }

    /**
     * 生成带日期的文件名
     * @param originalFilename 原始文件名
     * @return 新的文件名（包含日期路径）
     */
    public static String generateFileName(String originalFilename) {
        // 获取当前日期，格式：2024/01/15
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        // 生成随机 UUID
        String uuid = UUID.randomUUID().toString().replace("-", "");
        // 获取文件扩展名
        String extension = "";
        if (originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        // 组合完整路径：2024/01/15/uuid.jpg
        return datePath + "/" + uuid + extension;
    }

    /**
     * 获取文件访问 URL
     * @param objectName 对象名称
     * @return 完整 URL
     */
    private String getFileUrl(String objectName) {
        return "https://" + bucketName + "." + endpoint.replaceFirst("oss-", "") + "/" + objectName;
    }

    /**
     * 创建 OSS 客户端
     * @return OSS 客户端
     */
    private OSS createOSSClient() {
        return new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }
}
