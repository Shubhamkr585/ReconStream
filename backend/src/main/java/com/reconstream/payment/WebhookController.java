package com.reconstream.payment;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final WebhookProcessingService webhookProcessingService;

    public WebhookController(WebhookProcessingService webhookProcessingService) {
        this.webhookProcessingService = webhookProcessingService;
    }

    @PostMapping("/psp")
    public ResponseEntity<?> handlePspWebhook(
            @RequestHeader(value = "X-Signature", required = false) String headerSignature,
            @Valid @RequestBody PspWebhookRequest request) {
        return webhookProcessingService.processWebhook(request, headerSignature);
    }
}
