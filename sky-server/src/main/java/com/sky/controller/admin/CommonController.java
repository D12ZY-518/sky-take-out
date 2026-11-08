package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.utils.AliOssUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * 文件上传接口（管理端）
 */
@RestController
@RequestMapping("/admin/common")
@Slf4j
@Api(tags = "通用接口")
public class CommonController {

    @Autowired
    private AliOssUtil aliOssUtil;

    /**
     * 文件上传
     * @param file 上传的文件
     * @return 文件访问 URL
     */
    @PostMapping("/upload")
    @ApiOperation("文件上传")
    public Result<String> upload(MultipartFile file) {
        log.info("文件上传，文件名：{}", file.getOriginalFilename());

        try {
            // 生成对象名称（包含日期路径）
            String objectName = AliOssUtil.generateFileName(file.getOriginalFilename());
            
            // 上传到 OSS
            String url = aliOssUtil.upload(file.getInputStream(), objectName);
            
            log.info("文件上传成功，URL: {}", url);
            return Result.success(url);
        } catch (IOException e) {
            log.error("文件上传失败：{}", e.getMessage());
            return Result.error("文件上传失败：" + e.getMessage());
        }
    }
}
