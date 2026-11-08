package com.sky.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户信息更新 DTO
 */
@Data
public class UserInfoUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户昵称
     */
    private String name;

    /**
     * 用户头像
     */
    private String avatar;
}
