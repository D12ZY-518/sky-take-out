package com.sky.controller.admin;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.AdminTicketService;

@RestController
@RequestMapping("/admin/tickets")
public class TicketController {
    @Autowired
    private AdminTicketService service;

    @GetMapping
    public Result<PageResult> list(@RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int pageSize,
                                   @RequestParam(required = false) Integer status) {
        return Result.success(service.list(page, pageSize, status));
    }

    @PutMapping("/{id}/status")
    public Result<String> updateStatus(@PathVariable Long id,
                                       @RequestBody Map<String, Integer> body) {
        service.updateStatus(id, body.get("status"));
        return Result.success();
    }
}
