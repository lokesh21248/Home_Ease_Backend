package com.homeease.dispatch.controller;

import com.homeease.dispatch.dto.DispatchDto.*;
import com.homeease.dispatch.service.DispatchEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/v1/dispatch", "/internal/dispatch", "/api/dispatch"})
public class DispatchController {

    private final DispatchEngineService dispatchEngineService;

    public DispatchController(DispatchEngineService dispatchEngineService) {
        this.dispatchEngineService = dispatchEngineService;
    }

    @PostMapping({"/trigger", "/candidates"})
    public ResponseEntity<DispatchResultResponse> triggerSpatialMatching(@RequestBody TriggerDispatchRequest request) {
        return ResponseEntity.ok(dispatchEngineService.triggerSpatialMatching(request));
    }

    @PostMapping("/accept")
    public ResponseEntity<DispatchResultResponse> acceptJob(@RequestBody AcceptJobRequest request) {
        return ResponseEntity.ok(dispatchEngineService.acceptJob(request));
    }

    @PostMapping("/reject")
    public ResponseEntity<DispatchResultResponse> rejectJob(@RequestBody RejectJobRequest request) {
        return ResponseEntity.ok(dispatchEngineService.rejectJob(request));
    }
}
