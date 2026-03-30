package com.reconstream.recon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reconstream.config.KafkaConfig;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class SettlementIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SettlementIngestionService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public SettlementIngestionService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public SettlementUploadResponse ingestCsv(MultipartFile file) throws IOException {
        String batchId = "BATCH-" + UUID.randomUUID();
        int rowsQueued = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            for (CSVRecord record : parser) {
                String pspTxnId = record.get("psp_txn_id");
                Long grossAmount = Long.parseLong(record.get("gross_amount_paise"));
                Long fee = Long.parseLong(record.get("fee_paise"));
                Long netSettled = Long.parseLong(record.get("net_settled_paise"));
                String status = record.isSet("status") ? record.get("status") : "SETTLED";

                SettlementRow row = new SettlementRow(batchId, pspTxnId, grossAmount, fee, netSettled, status);
                String json = objectMapper.writeValueAsString(row);

                kafkaTemplate.send(KafkaConfig.TOPIC_BANK_SETTLEMENTS, pspTxnId, json);
                rowsQueued++;
            }
        }

        log.info("Ingested CSV batch {}: {} rows queued to Kafka topic {}", batchId, rowsQueued, KafkaConfig.TOPIC_BANK_SETTLEMENTS);
        return new SettlementUploadResponse(batchId, rowsQueued);
    }
}
