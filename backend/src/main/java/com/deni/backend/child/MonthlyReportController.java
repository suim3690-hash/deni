package com.deni.backend.child;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reports")
public class MonthlyReportController {

	private final MonthlyReportService monthlyReportService;

	public MonthlyReportController(MonthlyReportService monthlyReportService) {
		this.monthlyReportService = monthlyReportService;
	}

	@GetMapping("/monthly")
	MonthlyReportService.MonthlyReport getMonthlyReport(@RequestParam UUID childId, @RequestParam String month) {
		return monthlyReportService.getMonthlyReport(childId, month);
	}
}
