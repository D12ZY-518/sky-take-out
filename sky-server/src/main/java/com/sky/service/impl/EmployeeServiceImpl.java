package com.sky.service.impl;

import com.sky.mapper.EmployeeMapper;
import com.sky.service.EmployeeService;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.dto.PasswordEditDTO;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.result.PageResult;
import com.sky.constant.PasswordConstant;
import com.sky.constant.StatusConstant;
import com.sky.context.BaseContext;
import com.sky.entity.Employee;
import com.sky.exception.AccountLockedException;
import com.sky.exception.AccountNotFoundException;
import com.sky.exception.PasswordEditFailedException;
import com.sky.exception.PasswordErrorException;
import com.sky.exception.UsernameDuplicateException;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;



@Slf4j
@Service
public class EmployeeServiceImpl implements EmployeeService {

    @Autowired
    private EmployeeMapper employeeMapper;



    public Employee getById(Long id) {
        // 1. 参数保护：id 为空按账号不存在处理，抛业务异常
        if (id == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        // 2. 按 id 查询员工并返回
        return employeeMapper.getById(id);        
    }

    public Employee getByUsername(String username) {
        // 1. 按用户名精确查询员工
        Employee temp = employeeMapper.getByUsername(username);
        // 2. 查不到说明该用户名不存在，抛业务异常，避免把空对象继续上抛
        if (temp == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        return temp;
    }

    public void save(EmployeeDTO employeeDTO) {
        // 1. 新增前先按用户名查重：已存在则抛“用户名重复”异常（登录账号必须唯一）
        String username = employeeDTO.getUsername();
        Employee e = employeeMapper.getByUsername(username);
        if (e != null) {
            throw new UsernameDuplicateException(MessageConstant.USERNAME_DUPLICATE);
        }
        // 2. 组装新员工实体：先填创建/更新时间与当前操作人（审计字段）
        Employee employee = new Employee();
        employee.setCreateTime(LocalDateTime.now());
        employee.setUpdateTime(LocalDateTime.now());
        employee.setCreateUser(BaseContext.getCurrentId());
        employee.setUpdateUser(BaseContext.getCurrentId());
        // 3. 再填账号资料：初始密码用默认密码(123456)的 MD5 存储（密码不存明文），账号默认启用
        employee.setUsername(employeeDTO.getUsername());
        employee.setPassword(DigestUtils.md5DigestAsHex(PasswordConstant.DEFAULT_PASSWORD.getBytes()));
        employee.setName(employeeDTO.getName());
        employee.setPhone(employeeDTO.getPhone());
        employee.setSex(employeeDTO.getSex());
        employee.setIdNumber(employeeDTO.getIdNumber());
        employee.setStatus(StatusConstant.ENABLE);
        // 4. 执行插入
        employeeMapper.save(employee);
    }

    public void updateStatus(Long id, Integer status) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setStatus(status);
        employee.setUpdateUser(BaseContext.getCurrentId());
        employee.setUpdateTime(LocalDateTime.now());
        employeeMapper.update(employee);
    }

    public void update(EmployeeDTO employeeDTO) {
        Employee employee = new Employee();
        employee.setId(employeeDTO.getId());
        employee.setUsername(employeeDTO.getUsername());
        employee.setName(employeeDTO.getName());
        employee.setPhone(employeeDTO.getPhone());
        employee.setSex(employeeDTO.getSex());
        employee.setIdNumber(employeeDTO.getIdNumber());
        employee.setUpdateTime(LocalDateTime.now());
        employee.setUpdateUser(BaseContext.getCurrentId());
        employeeMapper.update(employee);
    }

    public PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        PageHelper.startPage(employeePageQueryDTO.getPage(), employeePageQueryDTO.getPageSize());        
        Page<Employee> page = employeeMapper.pageQuery(employeePageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    public Employee login(EmployeeLoginDTO request) {
        // 1. 按用户名查询账号：查不到说明该账号未注册，抛“账号不存在”异常
        Employee employee = employeeMapper.getByUsername(request.getUsername());
        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        // 2. 账号状态校验：被禁用（锁定）的账号禁止登录
        if(employee.getStatus() == StatusConstant.DISABLE){
            throw new AccountLockedException(MessageConstant.ACCOUNT_LOCKED);
        }
        // 3. 密码校验：明文密码 MD5 后与库中密文比对，不一致抛“密码错误”异常
        if (DigestUtils.md5DigestAsHex(request.getPassword().getBytes()).equals(employee.getPassword())) {
            return employee;
        } else {
            throw new PasswordErrorException(MessageConstant.PASSWORD_ERROR);
        }
    }

    public void editPassword(PasswordEditDTO passwordEditDTO){
        // 1. 按 id 查询员工：不存在则抛“账号不存在”异常
        Employee employee = employeeMapper.getById(passwordEditDTO.getEmpId());
        if (employee == null) {
            throw new AccountNotFoundException(MessageConstant.ACCOUNT_NOT_FOUND);
        }
        // 2. 校验旧密码：明文 MD5 后与库中密文比对，不一致不允许改密
        if (!employee.getPassword().equals(DigestUtils.md5DigestAsHex(passwordEditDTO.getOldPassword().getBytes()))) {
            throw new PasswordEditFailedException(MessageConstant.PASSWORD_EDIT_FAILED);
        }
        // 3. 校验通过后用新密码（MD5 加密存储）覆盖更新，并记录修改时间/操作人
        employee.setUpdateTime(LocalDateTime.now());
        employee.setUpdateUser(BaseContext.getCurrentId());
        employee.setPassword(DigestUtils.md5DigestAsHex(passwordEditDTO.getNewPassword().getBytes()));
        employeeMapper.update(employee);
    }
}