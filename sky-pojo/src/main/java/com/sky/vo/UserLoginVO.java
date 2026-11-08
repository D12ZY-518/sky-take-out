package com.sky.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginVO implements Serializable {

    private Long id;
    private String openid;
    private String token;
    
    // 用户信息
    private String name;
    private String avatar;
    
    // 标记是否需要完善信息（name 或 avatar 为空时需要）
    private Boolean needCompleteInfo;

}
