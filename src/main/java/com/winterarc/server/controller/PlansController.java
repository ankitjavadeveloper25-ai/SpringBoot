package com.winterarc.server.controller;

import com.winterarc.server.service.PlanCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
public class PlansController {

    private final PlanCatalogService planCatalogService;

    public PlansController(PlanCatalogService planCatalogService) {
        this.planCatalogService = planCatalogService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getPlans() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("plans", planCatalogService.getPublicCatalog());
        resp.put("defaultCurrency", "INR");
        resp.put("commercialNotice", "All orders are verified server-side with Razorpay 256-bit encryption. Plan durations remain configurable pending final business determination.");
        return ResponseEntity.ok(resp);
    }
}
