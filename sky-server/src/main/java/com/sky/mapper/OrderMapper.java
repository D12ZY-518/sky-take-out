package com.sky.mapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.github.pagehelper.Page;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.entity.Orders;

@Mapper
public interface OrderMapper {
    
    void insert(Orders order);  

    /**
     * 根据订单号和用户ID查询订单
     * @param orderNumber
     * @param userId
     * @return
     */
    @Select("select * from orders where number = #{orderNumber} and user_id = #{userId}")
    Orders getByNumberAndUserId(String orderNumber, Long userId);

    /**
     * 根据订单号查询订单（支付回调用：回调无登录用户，不能依赖 userId）
     * @param number
     * @return
     */
    @Select("select * from orders where number = #{number}")
    Orders getByNumber(String number);

    /**
     * 更新订单
     * @param order
     */
    void update(Orders order);  

    /**
     * 订单分页查询
     * @param ordersPageQueryDTO
     * @return
     */
    Page<Orders> pageQuery(OrdersPageQueryDTO ordersPageQueryDTO);

    /**
     * 根据订单ID和用户ID查询订单
     * @param id
     * @param userId
     * @return
     */
    @Select("select * from orders where id = #{id} and user_id = #{userId}")
    Orders getByIdAndUserId(Long id, Long userId);

    /**
     * 根据订单ID查询订单
     * @param id
     * @return
     */
    @Select("select * from orders where id = #{id}")
    Orders getById(Long id);

    /**
     * 根据订单状态查询订单数量
     * @param status
     * @return
     */
    @Select("select count(*) from orders where status = #{status}")
    Integer countByStatus(Integer status);   // @Select("select count(*) from orders where status = #{status}")

    /**
     * 营业额统计（XML 或注解，按日分组）
     * @param begin
     * @param end
     * @return
     */
    List<Map<String, Object>> getTurnoverByDate(LocalDate begin, LocalDate end);   // SUM(amount) where status=5 GROUP BY date
    
    /**
     * 订单数/有效订单数统计（XML 或注解，按日分组）
     * @param begin
     * @param end
     * @return
     */ 
    List<Map<String, Object>> getOrderCountByDate(LocalDate begin, LocalDate end); // COUNT(id) GROUP BY date
    /**
     * 有效订单数统计（XML 或注解，按日分组）
     * @param begin
     * @param end
     * @return
     */     
    List<Map<String, Object>> getValidOrderCountByDate(LocalDate begin, LocalDate end);   // COUNT(id) where status=5 GROUP BY date
    

    /**
     * 10 个最热门的菜品统计（XML 或注解，按日分组）
     * @param begin
     * @param end
     * @return
     */
    List<Map<String, Object>> getTop10(LocalDate begin, LocalDate end);            // 联查 order_detail+dish
}
