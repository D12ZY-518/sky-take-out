package com.sky.controller.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sky.context.BaseContext;
import com.sky.dto.UserLoginDTO;
import com.sky.result.Result;
import com.sky.service.UserService;
import com.sky.vo.UserLoginVO;

@RestController
@RequestMapping("/user/user")
public class UserController{    
    
    @Autowired
    private UserService userService;

    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDTO dto) {
        // 调用服务层方法进行登录
        UserLoginVO userLoginVO = userService.wxLogin(dto);
        // 返回登录结果
        return Result.success(userLoginVO);
    }

    @PostMapping("/logout")
    public Result<String> logout() {
        // 调用服务层方法进行退出登录
        BaseContext.removeCurrentId();
        // 返回成功结果
        return Result.success("退出登录成功");
    }
    
}
