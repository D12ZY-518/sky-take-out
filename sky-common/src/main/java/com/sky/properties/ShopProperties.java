package com.sky.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 店铺配置属性
 */
@Component
@ConfigurationProperties(prefix = "sky.shop")
@Data
public class ShopProperties {
    /**
     * 店铺营业状态：1 为营业，0 为打烊
     */
    private Integer status = 0;
}
