package com.sky.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.entity.CustomerServiceTicket;
import com.sky.exception.TicketBusinessException;
import com.sky.mapper.AdminTicketMapper;
import com.sky.result.PageResult;
import com.sky.service.AdminTicketService;

@Service
public class AdminTicketServiceImpl implements AdminTicketService {
    @Autowired
    private AdminTicketMapper mapper;

    @Override
    public PageResult list(int page, int pageSize, Integer status) {
        if (page < 1 || pageSize < 1 || pageSize > 100 ||
                (status != null && (status < CustomerServiceTicket.PENDING ||
                        status > CustomerServiceTicket.FINISHED))) {
            throw new TicketBusinessException("工单查询参数不合法");
        }
        PageHelper.startPage(page, pageSize);
        List<CustomerServiceTicket> rows = mapper.list(status);
        Page<CustomerServiceTicket> result = (Page<CustomerServiceTicket>) rows;
        return new PageResult(result.getTotal(), rows);
    }

    @Override
    @Transactional
    public void updateStatus(Long id, Integer nextStatus) {
        if (id == null || id <= 0 || nextStatus == null ||
                (nextStatus != CustomerServiceTicket.PROCESSING &&
                        nextStatus != CustomerServiceTicket.FINISHED)) {
            throw new TicketBusinessException("工单状态参数不合法");
        }
        CustomerServiceTicket ticket = mapper.getById(id);
        if (ticket == null) {
            throw new TicketBusinessException("工单不存在");
        }
        if (nextStatus.equals(ticket.getStatus())) return; // identical retry
        if (ticket.getStatus() == null || ticket.getStatus() != nextStatus - 1) {
            throw new TicketBusinessException("工单状态只能按待处理→处理中→已完结推进");
        }
        if (mapper.updateStatus(id, ticket.getStatus(), nextStatus) != 1) {
            throw new TicketBusinessException("工单已被其他操作更新，请刷新后重试");
        }
    }
}
