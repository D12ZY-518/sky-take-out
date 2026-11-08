package com.sky.controller.admin;

import java.util.List;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sky.dto.SetmealDTO;
import com.sky.dto.SetmealPageQueryDTO;
import com.sky.result.PageResult;
import com.sky.result.Result;
import com.sky.service.SetmealService;
import com.sky.vo.SetmealVO;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/admin/setmeal")
@Api(tags = "套餐相关接口")
@Slf4j
public class SetmealController {
    
    @Autowired
    private SetmealService setmealService;

    @CacheEvict(cacheNames = "setmealCache", key = "#setmealDTO.categoryId")
    @PostMapping()
    @ApiOperation("新增套餐")
    public Result<String> save(@Valid @RequestBody SetmealDTO setmealDTO) {
        log.info("新增套餐：{}", setmealDTO);
        setmealService.saveWithDish(setmealDTO);
        return Result.success();
    }

    @GetMapping("/page")
    @ApiOperation("分页查询")
    public Result<PageResult> page(SetmealPageQueryDTO setmealPageQueryDTO) {
        log.info("分页查询：{}", setmealPageQueryDTO);
        PageResult pageResult = setmealService.pageQuery(setmealPageQueryDTO);
        return Result.success(pageResult);
    }

    @CacheEvict(cacheNames = "setmealCache", allEntries = true)
    @DeleteMapping()
    @ApiOperation("批量删除")
    public Result<String> delete(@RequestParam List<Long> ids) {
        log.info("批量删除：{}", ids);
        setmealService.deleteByIds(ids);
        return Result.success();
    }

    @CacheEvict(cacheNames = "setmealCache",allEntries = true)
    @PutMapping()
    @ApiOperation("修改套餐")   
    public Result<String> update(@RequestBody SetmealDTO setmealDTO) {
        log.info("修改套餐：{}", setmealDTO);
        setmealService.update(setmealDTO);
        return Result.success();
    }

    @GetMapping("/{id}")
    @ApiOperation("根据id查询")
    public Result<SetmealVO> getByIdWithDish(@PathVariable Long id) {
        log.info("查询套餐：{}", id);
        SetmealVO setmealVO = setmealService.getByIdWithDish(id);
        return Result.success(setmealVO);
    }

    @CacheEvict(cacheNames = "setmealCache",allEntries = true)
    @PostMapping("/status/{status}")
    @ApiOperation("起售或停售套餐")
    public Result<String> startOrStop(@PathVariable Integer status, Long id) {
        log.info("套餐起售或停售：{}", id);
        setmealService.startOrStop(status, id);
        return Result.success();
    }
}
