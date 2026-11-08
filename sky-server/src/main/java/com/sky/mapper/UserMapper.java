package com.sky.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.User;

@Mapper
public interface UserMapper {
    
    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(String openid);

    /**
     * 新增用户
     * @param user
     */
    @Insert("insert into user (openid, name, phone, sex, avatar, create_time) values (#{openid}, #{name}, #{phone}, #{sex}, #{avatar}, #{createTime})")
    void insert(User user);

    /**
     * 根据id查询用户
     * @param id
     * @return
     */
    @Select("select * from user where id = #{id}")
    User getById(Long id);

    /**
     * 每日新增用户数（XML，按日分组）
     * @param begin
     * @param end
     * @return
     */
    List<Map<String, Object>> getNewUserByDate(LocalDate begin, LocalDate end);    // COUNT(id) GROUP BY date

    /**
     * 截止 begin（不含当天）的总用户数——用户统计的累计基准
     * @param begin
     * @return
     */
    @Select("select count(*) from user where create_time < #{begin}")
    Integer countTotalBefore(LocalDate begin);
    
}
