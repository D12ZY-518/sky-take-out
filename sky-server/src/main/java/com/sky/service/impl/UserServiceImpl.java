package com.sky.service.impl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;
import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.dto.UserLoginDTO;
import com.sky.entity.User;
import com.sky.exception.LoginFailedException;
import com.sky.mapper.UserMapper;
import com.sky.properties.JwtProperties;
import com.sky.properties.WeChatProperties;
import com.sky.service.UserService;
import com.sky.utils.HttpClientUtil;
import com.sky.utils.JwtUtil;
import com.sky.vo.UserLoginVO;

@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private WeChatProperties weChatProperties;

    @Autowired
    private JwtProperties jwtProperties;

    public UserLoginVO wxLogin(UserLoginDTO userLoginDTO) {
        // 1. 拼装微信登录凭证校验接口地址：用 appid/secret/js_code 换取 openid
        String appId = weChatProperties.getAppid();
        String appSecret = weChatProperties.getSecret();
        String code = userLoginDTO.getCode();
        String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + appId + "&secret=" + appSecret + "&js_code=" + code + "&grant_type=authorization_code";
        // 2. 请求微信接口并解析响应 JSON，取出 openid（用户在微信体系内的唯一标识）
        String response = HttpClientUtil.doGet(url, null);
        JSONObject jsonObject = JSONObject.parseObject(response);
        String openid = jsonObject.getString("openid");
        // 3. 拿不到 openid 说明 code 无效/已过期：抛登录失败异常，终止后续流程
        if (openid == null) {
            throw new LoginFailedException(MessageConstant.LOGIN_FAILED);
        }
        // 4. 按 openid 查用户：查不到说明是首次登录，自动注册新用户并记录注册时间
        User user = userMapper.getByOpenid(openid);
        if (user == null) {
            user = new User();
            user.setOpenid(openid);
            user.setCreateTime(LocalDateTime.now());
            userMapper.insert(user);
        }
        // 5. 生成登录返回结果：以用户 id 为凭据签发 JWT（后续请求凭 token 识别身份），组装后返回
        UserLoginVO userLoginVO = new UserLoginVO();
        userLoginVO.setId(user.getId());
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID, user.getId());
        String token = JwtUtil.createJWT(jwtProperties.getUserSecretKey(), jwtProperties.getUserTtl(), claims);
        userLoginVO.setOpenid(user.getOpenid());
        userLoginVO.setToken(token);
        return userLoginVO;
    }
    
}
