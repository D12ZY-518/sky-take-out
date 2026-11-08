package com.sky.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.sky.entity.CustomerServiceTicket;

@Mapper
public interface AdminTicketMapper {
    @Select("<script>select * from customer_service_ticket "
            + "<where><if test='status != null'>status = #{status}</if></where> "
            + "order by create_time desc, id desc</script>")
    List<CustomerServiceTicket> list(@Param("status") Integer status);

    @Select("select * from customer_service_ticket where id = #{id}")
    CustomerServiceTicket getById(Long id);

    @Update("update customer_service_ticket set status = #{nextStatus} "
            + "where id = #{id} and status = #{expectedStatus}")
    int updateStatus(@Param("id") Long id,
                     @Param("expectedStatus") Integer expectedStatus,
                     @Param("nextStatus") Integer nextStatus);
}
