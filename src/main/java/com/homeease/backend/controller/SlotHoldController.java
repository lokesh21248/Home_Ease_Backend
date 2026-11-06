package com.homeease.backend.controller;

import com.homeease.backend.dto.SlotHoldDto.*;
import com.homeease.backend.service.SlotHoldService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1", "/api"})
@CrossOrigin(origins = "*")
public class SlotHoldController {

    private final SlotHoldService slotHoldService;

    public SlotHoldController(SlotHoldService slotHoldService) {
        this.slotHoldService = slotHoldService;
    }

    /**
     * Capacity-controlled slot discovery with Redis caching (Phase 1).
     * GET /api/v1/slots?serviceId={}&pincode={}&date=YYYY-MM-DD
     */
    @GetMapping("/slots")
    public ResponseEntity<List<SlotWindow>> getSlots(
            @RequestParam("serviceId") UUID serviceId,
            @RequestParam(value = "pincode", required = false, defaultValue = "500072") String pincode,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate queryDate = (date != null) ? date : LocalDate.now();
        List<SlotWindow> slots = slotHoldService.getSlots(serviceId, pincode, queryDate);
        return ResponseEntity.ok(slots);
    }

    /**
     * Atomically holds a slot window for 10 minutes (Phase 1).
     * POST /api/v1/slot-holds
     */
    @PostMapping("/slot-holds")
    public ResponseEntity<HoldResponse> holdSlot(@Valid @RequestBody HoldRequest request) {
        HoldResponse response = slotHoldService.holdSlot(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Explicitly releases an active slot hold.
     * DELETE /api/v1/slot-holds/{id}?lockToken={}
     */
    @DeleteMapping("/slot-holds/{id}")
    public ResponseEntity<Void> releaseHold(
            @PathVariable("id") UUID holdId,
            @RequestParam("lockToken") String lockToken) {
        slotHoldService.releaseHold(holdId, lockToken);
        return ResponseEntity.noContent().build();
    }
}

