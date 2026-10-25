package com.payflow.payment.controller;

import com.payflow.payment.dto.ReconciliationReport;
import com.payflow.payment.service.ReconciliationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/reconciliation")
    public ReconciliationReport reconcile() {
        return reconciliationService.reconcile();
    }
}
