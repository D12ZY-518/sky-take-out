package com.sky.config;

import com.sky.interceptor.InternalTokenInterceptor;
import com.sky.interceptor.JwtTokenAdminInterceptor;
import com.sky.interceptor.JwtTokenUserInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

/**
 * 配置类，注册 web 层相关组件
 *
 * 【面试视角·本项目三套鉴权的注册矩阵】（跨语言架构里最容易讲混的一张表）
 *   路径            拦截器                      凭证             信任主体
 *   /admin/**       JwtTokenAdminInterceptor   admin JWT        管理端员工
 *   /user/**        JwtTokenUserInterceptor    user JWT         C 端用户
 *   /internal/**    InternalTokenInterceptor   X-Internal-Token 内部服务（Python Agent）
 *   两处演示豁免要能解释：/user/ai/** 和 /user/service/** 免登录放行，
 *   是"演示项目的取舍"，controller 内部 resolveUserId 会优先取真实 token、否则降级演示用户。
 */
@Configuration
@Slf4j
public class WebMvcConfiguration extends WebMvcConfigurationSupport {
    private static final Logger log = LoggerFactory.getLogger(WebMvcConfiguration.class);

    @Autowired
    private JwtTokenAdminInterceptor jwtTokenAdminInterceptor;

    @Autowired
    private JwtTokenUserInterceptor jwtTokenUserInterceptor;

    @Autowired
    private InternalTokenInterceptor internalTokenInterceptor;

    /**
     * 注册自定义拦截器
     *
     * 【学习要点】注册顺序即执行顺序（同类路径下先注册的先 preHandle）。
     * /internal/** 与 /user/** 互斥，所以内部请求不会先撞上 JWT 拦截器；
     * excludePathPatterns 只影响它所属的那一条注册，互不干扰。
     *
     * @param registry
     */
    protected void addInterceptors(InterceptorRegistry registry) {
        log.info("开始注册自定义拦截器...");
        // 注册管理端拦截器
        registry.addInterceptor(jwtTokenAdminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns("/admin/employee/login");

        // 注册小程序端拦截器
        registry.addInterceptor(jwtTokenUserInterceptor)
                .addPathPatterns("/user/**")
                .excludePathPatterns("/user/user/login")  // 正确的排除路径
                .excludePathPatterns("/user/shop/status")
                .excludePathPatterns("/user/ai/**")        // AI 点餐助手演示入口（免登录，controller 内部再按 token 降级）
                .excludePathPatterns("/user/service/**");  // Python Agent 售后客服演示入口（同上，免登录演示）

        // 内部接口：不走用户 JWT，单独校验服务间共享令牌 X-Internal-Token
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/internal/**");
    }

    /**
     * 通过 knife4j 生成接口文档
     * @return
     */
    @Bean
    public Docket docket() {
        ApiInfo apiInfo = new ApiInfoBuilder()
                .title("禾巷餐饮项目接口文档")
                .version("2.0")
                .description("禾巷餐饮项目接口文档")
                .build();
        Docket docket = new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo)
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.sky.controller"))
                .paths(PathSelectors.any())
                .build();
        return docket;
    }

    /**
     * 设置静态资源映射
     * @param registry
     */
    protected void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 继承 WebMvcConfigurationSupport 会关闭默认静态资源映射，需手动补 classpath:/static/
        registry.addResourceHandler("/**").addResourceLocations("classpath:/static/");
        registry.addResourceHandler("/doc.html").addResourceLocations("classpath:/META-INF/resources/");
        registry.addResourceHandler("/webjars/**").addResourceLocations("classpath:/META-INF/resources/webjars/");
    }

    /**
     * 配置跨域访问（解决小程序、前端访问 OSS 图片 403 问题）
     * @return
     */
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许所有域名进行跨域调用
        config.addAllowedOriginPattern("*");
        // 允许跨域发送 cookie
        config.setAllowCredentials(true);
        // 允许全部请求方法
        config.addAllowedMethod("*");
        // 允许全部 headers
        config.addAllowedHeader("*");
        // 暴露以下 headers 给前端
        config.addExposedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
