package com.sky.controller.admin;

import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.dto.PasswordEditDTO;
import com.sky.entity.Employee;
import com.sky.exception.AccountNotFoundException;
import com.sky.service.EmployeeService;
import com.sky.utils.JwtUtil;
import com.sky.vo.EmployeeLoginVO;
import com.sky.properties.JwtProperties;
import java.util.HashMap;
import java.util.Map;

import com.sky.constant.JwtClaimsConstant;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.DigestUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/employee")
public class EmployeeController {
    
    @Autowired
    private EmployeeService employeeServiceImpl;

    @Autowired
    private JwtProperties jwtProperties;

    @GetMapping("/{id}")
    public Result<Employee> getEmployeeById(@PathVariable Long id) {
        Employee employee = employeeServiceImpl.getById(id);
        if (employee != null) {
            return Result.success(employee);
        } else {
            return Result.error(MessageConstant.EMPLOYEE_NOT_FOUND);
        }
    }

    @PostMapping("/login")
    public Result<EmployeeLoginVO> login(@RequestBody EmployeeLoginDTO request) {
        Employee employee = employeeServiceImpl.login(request);
        Map<String, Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.EMP_ID, employee.getId());
        claims.put(JwtClaimsConstant.USERNAME, employee.getUsername());
        String token = JwtUtil.createJWT(jwtProperties.getAdminSecretKey(), jwtProperties.getAdminTtl(), claims);
        EmployeeLoginVO employeeLoginVO = new EmployeeLoginVO();
        BeanUtils.copyProperties(employee, employeeLoginVO);
        employeeLoginVO.setUserName(employee.getUsername());
        employeeLoginVO.setToken(token);
        return Result.success(employeeLoginVO);
    }


    @PostMapping
    public Result<String> saveEmployee(@RequestBody EmployeeDTO employeeDTO) {
        employeeServiceImpl.save(employeeDTO);
        return Result.success();
    }

    @GetMapping("/page")
    public Result<PageResult> pageQuery(EmployeePageQueryDTO employeePageQueryDTO) {
        PageResult pageResult = employeeServiceImpl.pageQuery(employeePageQueryDTO);
        return Result.success(pageResult);
    }

    @PostMapping("/status/{status}")
    public Result<String> startOrStop(@PathVariable Integer status, Long id) {        
        employeeServiceImpl.updateStatus(id, status);
        return Result.success();
    }

    @PutMapping
    public Result<String> updateEmployee(@RequestBody EmployeeDTO employeeDTO) {
        employeeServiceImpl.update(employeeDTO);
        return Result.success("Employee updated successfully");
    }

    @PostMapping("/logout")
    public Result<String> logout() {
        // Implement logout logic, e.g., invalidate session or token
        return Result.success("Logged out successfully");
    }

    @PutMapping("/editPassword")
    public Result<String> editPassword(@RequestBody PasswordEditDTO passwordEditDTO){
        employeeServiceImpl.editPassword(passwordEditDTO);
        return Result.success();
    }
    


}