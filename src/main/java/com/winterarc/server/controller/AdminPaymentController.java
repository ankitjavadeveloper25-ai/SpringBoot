package com.winterarc.server.controller;

import com.winterarc.server.model.PaymentTransaction;
import com.winterarc.server.repository.PaymentTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payments")
public class AdminPaymentController {

    private final PaymentTransactionRepository paymentRepository;

    public AdminPaymentController(PaymentTransactionRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @GetMapping
    public ResponseEntity<?> getPayments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<PaymentTransaction> transactions = paymentRepository.findAllByOrderByCreatedAtDesc(pageable);
        return ResponseEntity.ok(transactions);
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportPaymentsCsv() {
        Page<PaymentTransaction> transactions = paymentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10000));
        StringBuilder sb = new StringBuilder();
        sb.append("Transaction ID,Provider,Event ID,Event Type,Amount Cents,Currency,Status,User ID,Created At\n");
        for (PaymentTransaction tx : transactions.getContent()) {
            sb.append(escapeCsv(tx.getId())).append(",")
              .append(escapeCsv(tx.getProvider())).append(",")
              .append(escapeCsv(tx.getEventId())).append(",")
              .append(escapeCsv(tx.getEventType())).append(",")
              .append(tx.getAmountCents()).append(",")
              .append(escapeCsv(tx.getCurrency())).append(",")
              .append(escapeCsv(tx.getStatus())).append(",")
              .append(escapeCsv(tx.getUser() != null ? tx.getUser().getId() : "")).append(",")
              .append(escapeCsv(tx.getCreatedAt().toString())).append("\n");
        }
        byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"winterarc-payment-transactions.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private String escapeCsv(String input) {
        if (input == null) return "\"\"";
        String val = input;
        if (val.startsWith("=") || val.startsWith("+") || val.startsWith("-") || val.startsWith("@") || val.startsWith("\t") || val.startsWith("\r")) {
            val = "'" + val;
        }
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }
}
