package com.sky.service;

import com.sky.result.PageResult;

public interface AdminTicketService {
    PageResult list(int page, int pageSize, Integer status);

    void updateStatus(Long id, Integer nextStatus);
}
