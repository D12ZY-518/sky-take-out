package com.sky.service.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;

@Service
public class ReportServiceImpl implements ReportService {
    @Autowired
    private OrderMapper orderMapper;

    @Autowired
    private UserMapper userMapper;

    @Override
    public TurnoverReportVO turnoverStatistics(LocalDate begin, LocalDate end) {
        // 1. 时间轴：begin 到 end 每一天
        List<LocalDate> dateRange = new ArrayList<>();
        LocalDate temp = begin;
        while (!temp.isAfter(end)) {
            dateRange.add(temp);
            temp = temp.plusDays(1);
        }

        // 2. SQL 结果转成 map：date -> turnover（SQL 返回 {date, turnover}）
        Map<LocalDate, BigDecimal> turnoverMap = new HashMap<>();
        for (Map<String, Object> m : orderMapper.getTurnoverByDate(begin, end)) {
            turnoverMap.put(LocalDate.parse(String.valueOf(m.get("date"))), (BigDecimal) m.get("turnover"));
        }

        // 3. 按时间轴取值（没有的补 0），保证 dateList 和 turnoverList 长度严格一致
        List<BigDecimal> turnoverValues = new ArrayList<>();
        for (LocalDate d : dateRange) {
            turnoverValues.add(turnoverMap.getOrDefault(d, BigDecimal.ZERO));
        }

        // 4. 把每日日期与营业额分别拼成逗号分隔字符串（与时间轴顺序一致，前端图表按序消费）
        String dateList = dateRange.stream().map(LocalDate::toString).collect(Collectors.joining(","));
        String turnoverList = turnoverValues.stream().map(BigDecimal::toString).collect(Collectors.joining(","));

        return TurnoverReportVO.builder().dateList(dateList).turnoverList(turnoverList).build();
    }
    @Override
    public UserReportVO userStatistics(LocalDate begin, LocalDate end) {
        // 1. 时间轴：begin 到 end 每一天
        List<LocalDate> dateRange = new ArrayList<>();
        LocalDate temp = begin;
        while (!temp.isAfter(end)) {
            dateRange.add(temp);
            temp = temp.plusDays(1);
        }
        String dateList = dateRange.stream().map(LocalDate::toString).collect(Collectors.joining(","));
        // 2. 基准 = begin 之前的总用户（count(id) where create_time < begin），作为逐日累加的起点
        Integer baseTotal = userMapper.countTotalBefore(begin);
        // 3. 新增按日 map（date -> count）
        Map<LocalDate, Integer> newUserMap = new HashMap<>();
        for (Map<String, Object> map : userMapper.getNewUserByDate(begin, end)) {
            newUserMap.put(LocalDate.parse(String.valueOf(map.get("date"))), ((Number) map.get("count")).intValue());
        }
        // 4. 按时间轴取值（新增补 0），保证长度一致；并在基准之上逐日累加出每日总用户
        List<Integer> newUserValues = new ArrayList<>();
        List<Integer> totalValues = new ArrayList<>();
        int acc = baseTotal;
        for (LocalDate d : dateRange) {
            Integer dailyNew = newUserMap.getOrDefault(d, 0);
            newUserValues.add(dailyNew);
            acc += dailyNew;
            totalValues.add(acc);
        }
        // 5. 把日期/新增/总用户三组列表拼成逗号分隔字符串，封装为报表 VO 返回
        String newUserStr = newUserValues.stream().map(String::valueOf).collect(Collectors.joining(","));
        String totalUserListStr = totalValues.stream().map(String::valueOf).collect(Collectors.joining(","));
        UserReportVO userReportVO = UserReportVO.builder()
                .newUserList(newUserStr)
                .dateList(dateList)
                .totalUserList(totalUserListStr)
                .build();
        return userReportVO;
    }
    @Override
    public OrderReportVO ordersStatistics(LocalDate begin, LocalDate end) {
         // 1. 时间轴：begin 到 end 每一天
        List<LocalDate> dateRange = new ArrayList<>();
        LocalDate temp = begin;
        while (!temp.isAfter(end)) {
            dateRange.add(temp);
            temp = temp.plusDays(1);
        }
        String dateList = dateRange.stream().map(LocalDate::toString).collect(Collectors.joining(","));
        // 2. SQL 结果转成 map：date -> orderCount（SQL 返回 {date, orderCount}）
        Map<LocalDate, Integer> orderCountMap = new HashMap<>();
        for (Map<String, Object> m : orderMapper.getOrderCountByDate(begin, end)) {
            orderCountMap.put(LocalDate.parse(String.valueOf(m.get("date"))), ((Number) m.get("orderCount")).intValue());
        }
        // 3. SQL 结果转成 map：date -> validOrderCount（SQL 返回 {date, validOrderCount}）
        Map<LocalDate, Integer> validOrderCountMap = new HashMap<>();
        for (Map<String, Object> m : orderMapper.getValidOrderCountByDate(begin, end)) {
            validOrderCountMap.put(LocalDate.parse(String.valueOf(m.get("date"))), ((Number) m.get("validOrderCount")).intValue());
        }   
        // 4. 按时间轴取值（没有的补 0），保证 dateList 和 orderCountList 长度严格一致
        List<Integer> orderCountValues = new ArrayList<>();
        for (LocalDate d : dateRange) {
            orderCountValues.add(orderCountMap.getOrDefault(d, 0));
        }
        String orderCountList = orderCountValues.stream().map(String::valueOf).collect(Collectors.joining(","));
        // 5. 按时间轴取值（没有的补 0），保证 dateList 和 validOrderCountList 长度严格一致
        List<Integer> validOrderCountValues = new ArrayList<>();
        for (LocalDate d : dateRange) {
            validOrderCountValues.add(validOrderCountMap.getOrDefault(d, 0));
        }
        // 6. 计算订单完成率：总订单数为 0 时置 0.0，避免除零异常
        Integer totalOrderCount = orderCountValues.stream().reduce(Integer::sum).orElse(0);
        Integer validOrderCount = validOrderCountValues.stream().reduce(Integer::sum).orElse(0);
        Double orderCompletionRate = totalOrderCount == 0 ? 0.0 : validOrderCount.doubleValue() / totalOrderCount.doubleValue();
        // 7. 拼接有效订单列表为逗号分隔字符串，与汇总指标一起封装成报表 VO 返回
        String validOrderCountList = validOrderCountValues.stream().map(String::valueOf).collect(Collectors.joining(","));
        return OrderReportVO.builder()
                    .dateList(dateList)
                    .orderCountList(orderCountList)
                    .validOrderCountList(validOrderCountList)
                    .totalOrderCount(totalOrderCount)
                    .validOrderCount(validOrderCount)
                    .orderCompletionRate(orderCompletionRate)   
                    .build();   
    }
    @Override
    public SalesTop10ReportVO top10(LocalDate begin, LocalDate end) {
        // 1. 查询销量 Top10 商品（SQL 内已按销量聚合、倒序返回）
        List<Map<String, Object>> top10List = orderMapper.getTop10(begin, end);
        List<String> nameList = new ArrayList<>();
        List<Integer> numberList = new ArrayList<>();
        // 2. 遍历查询结果，把商品名称与销量拆到两个列表
        for (Map<String, Object> m : top10List) {
            nameList.add((String) m.get("name"));
            numberList.add(((Number) m.get("number")).intValue());
        }
        // 3. 用 join 拼字符串，避免 List.toString() 的 [ ] 和逗号后空格
        String nameListStr = nameList.stream().collect(Collectors.joining(","));
        String numberListStr = numberList.stream().map(String::valueOf).collect(Collectors.joining(","));
        return SalesTop10ReportVO.builder()
                .nameList(nameListStr)
                .numberList(numberListStr)
                .build();   
    }
    @Override
    public void exportBusinessData(HttpServletResponse response) throws IOException {
        // 1. 导出近 30 天（不含今天）营业数据，概览汇总式
        LocalDate begin = LocalDate.now().minusDays(30);
        LocalDate end = LocalDate.now().minusDays(1);

        // 2. 汇总口径复用三个统计方法：
        // 营业额 = 每日已完成订单金额合计；有效订单/完成率 = 订单统计结果；新增用户 = 每日新增合计
        BigDecimal turnover = sumDecimalList(turnoverStatistics(begin, end).getTurnoverList());
        OrderReportVO orderReport = ordersStatistics(begin, end);
        long newUsers = sumIntList(userStatistics(begin, end).getNewUserList());
        // 3. 计算平均客单价：有效订单为 0 时记 0（防除零），否则 营业额 ÷ 有效订单数 并保留两位小数
        double avgUnitPrice = orderReport.getValidOrderCount() == 0
                ? 0.0
                : turnover.divide(new BigDecimal(orderReport.getValidOrderCount()), 2, RoundingMode.HALF_UP).doubleValue();

        // 4. 设置响应头：告诉浏览器这是 xlsx 附件下载（文件名做 URL 编码，防止中文乱码）
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("运营数据报表", StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment;filename=" + fileName + ".xlsx");

        // 5. 创建 Excel 工作簿与“运营数据”工作表，并设置前两列列宽（列宽以 1/256 字符宽度为单位）
        try (Workbook workbook = new XSSFWorkbook();
             OutputStream out = response.getOutputStream()) {
            Sheet sheet = workbook.createSheet("运营数据");
            sheet.setColumnWidth(0, 22 * 256);
            sheet.setColumnWidth(1, 22 * 256);

            // 6. 标题行 / 日期范围行（都跨两列合并居中）：准备标题 16 号加粗居中与正文居中两种样式
            CellStyle titleStyle = workbook.createCellStyle();
            titleStyle.setAlignment(HorizontalAlignment.CENTER);
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            titleStyle.setFont(titleFont);
            CellStyle centerStyle = workbook.createCellStyle();
            centerStyle.setAlignment(HorizontalAlignment.CENTER);

            // 7. 写入标题与日期范围单元格，并各自跨两列合并（整行居中显示）
            Row row0 = sheet.createRow(0);
            Cell titleCell = row0.createCell(0);
            titleCell.setCellValue("营业数据");
            titleCell.setCellStyle(titleStyle);
            Row row1 = sheet.createRow(1);
            Cell dateCell = row1.createCell(0);
            dateCell.setCellValue(begin + " 至 " + end);
            dateCell.setCellStyle(centerStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 1));
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));

            // 8. 订单完成率用百分比样式（写入时套用，展示成 0.0% 格式）
            CellStyle percentStyle = workbook.createCellStyle();
            percentStyle.setDataFormat(workbook.createDataFormat().getFormat("0.0%"));

            // 9. 指标行：label + value（营业额、完成率、有效订单、平均客单价、新增用户）
            Object[][] data = {
                    {"营业额(元)", turnover.doubleValue()},
                    {"订单完成率", orderReport.getOrderCompletionRate()},
                    {"有效订单", orderReport.getValidOrderCount()},
                    {"平均客单价(元)", avgUnitPrice},
                    {"新增用户", newUsers}
            };
            // 10. 从第 3 行起逐行写入指标：数值按 Double/Integer/Long 类型写入，订单完成率单元格套百分比样式
            int rowIdx = 3;
            for (Object[] kv : data) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue((String) kv[0]);
                Cell valueCell = row.createCell(1);
                Object value = kv[1];
                if (value instanceof Double) {
                    valueCell.setCellValue((Double) value);
                    if ("订单完成率".equals(kv[0])) {
                        valueCell.setCellStyle(percentStyle);
                    }
                } else if (value instanceof Integer) {
                    valueCell.setCellValue((Integer) value);
                } else if (value instanceof Long) {
                    valueCell.setCellValue((Long) value);
                }
            }
            // 11. 将工作簿写入响应输出流（try-with-resources 自动关闭资源）
            workbook.write(out);
        }
    }

    /** 把 "1,2,3" 这样的整数字符串列表求和 */
    private long sumIntList(String csv) {
        return Arrays.stream(csv.split(",")).filter(s -> !s.isEmpty()).mapToLong(Long::parseLong).sum();
    }

    /** 把 "1.0,2.5" 这样的金额字符串列表求和 */
    private BigDecimal sumDecimalList(String csv) {
        return Arrays.stream(csv.split(","))
                .filter(s -> !s.isEmpty())
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
