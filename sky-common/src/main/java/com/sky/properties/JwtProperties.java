package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sky.jwt")
@Data
public class JwtProperties {

    /**
     * 管理端员工生成jwt令牌相关配置
     */
    private String adminSecretKey;
    private long adminTtl;
    private String adminTokenName;

    /**
     * 用户端微信用户生成jwt令牌相关配置
     */
    private String userSecretKey;
    private long userTtl;
    private String userTokenName;

    // 手动添加getter方法以确保编译通过
    public String getAdminSecretKey() {
        return adminSecretKey;
    }

    public long getAdminTtl() {
        return adminTtl;
    }

    public String getAdminTokenName() {
        return adminTokenName;
    }

    public String getUserSecretKey() {
        return userSecretKey;
    }

    public long getUserTtl() {
        return userTtl;
    }

    public String getUserTokenName() {
        return userTokenName;
    }

    // 手动添加setter方法以确保配置绑定成功
    public void setAdminSecretKey(String adminSecretKey) {
        this.adminSecretKey = adminSecretKey;
    }

    public void setAdminTtl(long adminTtl) {
        this.adminTtl = adminTtl;
    }

    public void setAdminTokenName(String adminTokenName) {
        this.adminTokenName = adminTokenName;
    }

    public void setUserSecretKey(String userSecretKey) {
        this.userSecretKey = userSecretKey;
    }

    public void setUserTtl(long userTtl) {
        this.userTtl = userTtl;
    }

    public void setUserTokenName(String userTokenName) {
        this.userTokenName = userTokenName;
    }
}
