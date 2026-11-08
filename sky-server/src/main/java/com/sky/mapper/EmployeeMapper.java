package com.sky.mapper;

import org.apache.ibatis.annotations.Select;
import com.github.pagehelper.Page;
import com.sky.dto.EmployeePageQueryDTO;
import com.sky.entity.Employee;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EmployeeMapper {

    @Select("select * from employee where id = #{id}")
    Employee getById(Long id);

    @Select("select * from employee where username = #{username}")
    Employee getByUsername(String username);

    Page<Employee> pageQuery(EmployeePageQueryDTO employeePageQueryDTO);

    void save(Employee employee);

    void update(Employee employee);


}