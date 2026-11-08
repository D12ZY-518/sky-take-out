# 阿里云 OSS 配置与使用指南

## 📋 第一步：开通阿里云 OSS

### 1. 访问阿里云
- 网址：https://www.aliyun.com/
- 登录账号（没有就注册）

### 2. 开通 OSS 服务
- 搜索 "对象存储 OSS"
- 点击"立即开通"
- 按提示完成开通（有免费额度）

---

## 📦 第二步：创建 Bucket

### 1. 进入 OSS 控制台
- 网址：https://oss.console.aliyun.com/
- 点击左侧 **"Bucket 列表"** → **"创建 Bucket"**

### 2. 填写配置
```
地域：选择离你近的，如"华东 2(上海)"或"华北 2(北京)"
名称：sky-itcast-你的姓名拼音（必须全局唯一）
  例如：sky-itcast-zhangsan、sky-itcast-lisi
读写权限：私有（推荐）或公共读
存储类型：标准存储
其他默认
```

### 3. 记录重要信息
创建成功后，记下以下信息：
- **Bucket 名称**：sky-itcast-xxx
- **Endpoint**：如 oss-cn-beijing.aliyuncs.com

---

## 🔑 第三步：获取 AccessKey（推荐用 RAM 用户）

### 方式 A：创建 RAM 用户（更安全）✅

#### 1. 访问 RAM 控制台
- 网址：https://ram.console.aliyun.com/users

#### 2. 创建用户
- 点击 **"创建用户"**
- 登录名称：`oss-user`
- ✅ 勾选 **"OpenAPI 调用访问"**
- 点击"确定"

#### 3. 保存 AccessKey（⚠️ 只显示一次！）
创建成功后，立即复制保存：
```
AccessKey ID: LTAI5t...（示例）
AccessKey Secret: xxxxxxxxxxxxxxxx（示例）
```

#### 4. 授权 OSS 权限
- 在用户列表中点击刚创建的用户
- 点击 **"权限管理"** → **"新增授权"**
- 搜索并选择：**`AliyunOSSFullAccess`**
- 点击"确定"

### 方式 B：使用主账号 AccessKey（简单但不推荐）
- 网址：https://ram.console.aliyun.com/manage/ak
- 直接创建 AccessKey
- 复制保存 AccessKey ID 和 Secret

---

## ⚙️ 第四步：配置到项目

### 1. 修改 application-dev.yml

打开文件：`sky-server/src/main/resources/application-dev.yml`

找到并修改以下内容：
```yaml
sky:
  alioss:
    endpoint: oss-cn-beijing.aliyuncs.com  # 改成你的 Bucket 所在地域
    access-key-id: 你的 AccessKey ID        # 粘贴你的 AccessKey ID
    access-key-secret: 你的 AccessKey Secret # 粘贴你的 AccessKey Secret
    bucket-name: sky-itcast-你的 bucket 名称  # 改成你的 Bucket 名称
    base-path: https://${sky.alioss.bucket-name}.${sky.alioss.endpoint}
```

### 2. 重启 Spring Boot 服务
- 停止当前运行的服务
- 重新编译：`mvn clean package -DskipTests`
- 启动服务：`java -jar sky-server/target/sky-server-1.0-SNAPSHOT.jar`

启动日志中会显示：
```
开始配置 OSS CORS 规则...
OSS CORS 规则配置成功！
Bucket: sky-itcast-xxx
访问地址：https://sky-itcast-xxx.oss-cn-beijing.aliyuncs.com
```

---

## 🚀 第五步：测试上传

### 方法 1：使用 Swagger/Knife4j 测试

#### 1. 访问接口文档
- 打开浏览器：http://localhost:8080/doc.html
- 找到 **"通用接口"** -> **"文件上传"**

#### 2. 测试上传
- 点击 **"文件上传"** 接口
- 点击 **"Try it out"**
- 选择一个图片文件
- 点击 **"Execute"**

#### 3. 查看结果
返回示例：
```json
{
  "code": 1,
  "msg": "success",
  "data": "https://sky-itcast-xxx.oss-cn-beijing.aliyuncs.com/2024/01/15/uuid-xxxx.jpg"
}
```

### 方法 2：使用 Postman 测试

#### 1. 创建请求
- Method: POST
- URL: http://localhost:8080/admin/common/upload
- Headers: 添加管理员 token（如果需要）
- Body: form-data
  - Key: file (选择 File)
  - Value: 选择一个图片

#### 2. 发送请求
点击 Send，查看返回的 URL

---

## 📸 第六步：在小程序中使用

### 小程序上传图片代码示例

```javascript
// 选择图片
wx.chooseImage({
  count: 1,
  success: function(res) {
    const tempFilePath = res.tempFilePaths[0];
    
    // 上传到后端
    wx.uploadFile({
      url: 'http://localhost:8080/user/common/upload',
      filePath: tempFilePath,
      name: 'file',
      success: function(res) {
        const data = JSON.parse(res.data);
        if (data.code === 1) {
          // 获取 OSS 图片 URL
          const imageUrl = data.data;
          console.log('图片上传成功:', imageUrl);
          
          // 可以在页面中使用这个 URL
          // <image src="{{imageUrl}}" />
        }
      }
    });
  }
});
```

---

## 🔧 常见问题排查

### Q1: AccessKey 在哪里？
A: 在 RAM 控制台创建用户时生成，只展示一次！如果丢失了，需要重新创建。

### Q2: Bucket 名称提示已存在？
A: Bucket 名称全局唯一，换个独特的名字，如 `sky-itcast-yourname-2024`

### Q3: 上传失败，提示签名错误？
A: 检查 AccessKey ID 和 Secret 是否复制正确，不要有多余空格

### Q4: 图片上传成功但小程序无法访问？
A: 
1. 检查 OSS CORS 规则是否配置成功（看启动日志）
2. 手动去 OSS 控制台配置：
   - 进入 Bucket → 数据安全 → 跨域设置
   - 创建规则：来源*，方法 GET/POST，Headers *

### Q5: 费用问题？
A: 阿里云 OSS 有免费额度：
- 标准存储：5GB
- 下行流量：10GB/月
- 请求次数：20000 次/月
个人学习完全够用！

---

## 💡 最佳实践

### 1. 文件名规范
代码已自动生成带日期的文件名：
```
原始：avatar.jpg
OSS: 2024/01/15/a1b2c3d4-e5f6-7890-abcd-ef1234567890.jpg
```

### 2. 目录结构
建议按业务分类存储：
```
dish/        # 菜品图片
  2024/01/15/xxx.jpg
setmeal/     # 套餐图片
  2024/01/15/yyy.png
avatar/      # 用户头像
  2024/01/15/zzz.jpg
```

### 3. 图片优化
上传前建议压缩：
- 菜品图：800x600 即可
- 头像：200x200
- 格式：JPG（照片）或 PNG（透明背景）

---

## 🎯 完整流程总结

1. ✅ 开通阿里云 OSS
2. ✅ 创建 Bucket（记录名称和 Endpoint）
3. ✅ 创建 RAM 用户并获取 AccessKey
4. ✅ 授权 OSS 权限
5. ✅ 配置到 application-dev.yml
6. ✅ 重启 Spring Boot 服务
7. ✅ 通过 Swagger 测试上传
8. ✅ 在小程序中使用上传接口

---

## 📞 需要帮助？

如果遇到问题，检查：
1. 启动日志是否有 OSS 配置成功信息
2. AccessKey 是否正确
3. Bucket 名称是否正确
4. OSS 控制台 CORS 规则是否生效

可以查看启动日志或提供错误信息寻求帮助！
