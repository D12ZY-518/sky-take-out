package com.sky.service;

import com.sky.result.PageResult;
import com.sky.dto.EmployeeDTO;
import com.sky.dto.EmployeeLoginDTO;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.dto.PasswordEditDTO;
import com.sky.entity.Employee;


public interface EmployeeService {
    Employee getById(Long id);
    Employee getByUsername(String username);
    void save(EmployeeDTO employeeDTO);
    void updateStatus(Long id, Integer status);
    void update(EmployeeDTO employeeDTO);
    PageResult pageQuery(EmployeePageQueryDTO employeePageQueryDTO);

    Employee login(EmployeeLoginDTO request);

    void editPassword(PasswordEditDTO passwordEditDTO);
}