package com.reconstream.simulator;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/simulator")
public class SimulatorController {

    private final ChaosSimulatorService simulatorService;

    public SimulatorController(ChaosSimulatorService simulatorService) {
        this.simulatorService = simulatorService;
    }

    @PostMapping("/chaos-run")
    public ResponseEntity<ChaosSimulationSummary> runChaosSimulation(
            @RequestBody(required = false) ChaosRunRequest request) {
        return ResponseEntity.ok(simulatorService.runSimulation(request));
    }

    @GetMapping("/psp-status/{externalOrderId}")
    public ResponseEntity<PspStatusResponse> getPspStatus(@PathVariable String externalOrderId) {
        PspStatusResponse status = simulatorService.getGroundTruthStatus(externalOrderId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}
