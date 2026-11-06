package com.homeease.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.model.entity.BookingCartItem;
import com.homeease.backend.model.entity.Coupon;
import com.homeease.backend.model.enums.DiscountModel;
import com.homeease.backend.repository.CouponRepository;
import lombok.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
public class PricingEngineService {

    private static final Logger log = LoggerFactory.getLogger(PricingEngineService.class);
    private static final BigDecimal GST_RATE = new BigDecimal("0.18");
    private static final BigDecimal STANDARD_CONVENIENCE_FEE = new BigDecimal("49.00");

    private final CouponRepository couponRepository;
    private final ObjectMapper objectMapper;

    public PricingEngineService(CouponRepository couponRepository, ObjectMapper objectMapper) {
        this.couponRepository = couponRepository;
        this.objectMapper = objectMapper;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PriceBreakdown {
        private BigDecimal subtotal;
        private BigDecimal additionalCharges;
        private BigDecimal taxAmount;
        private BigDecimal discountAmount;
        private BigDecimal finalPayableAmount;
        private String couponCode;
        private Coupon appliedCoupon;
        private String breakdownJson;
        private List<ItemLineSnapshot> itemSnapshots;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemLineSnapshot {
        private UUID cartItemId;
        private UUID serviceId;
        private String serviceName;
        private UUID durationId;
        private String durationLabel;
        private UUID packageId;
        private String packageName;
        private UUID subServiceId;
        private String subServiceName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }

    /**
     * Compute authoritative server-side pricing breakdown
     */
    public PriceBreakdown calculateCartPricing(List<BookingCartItem> items, String rawCouponCode) {
        BigDecimal subtotal = BigDecimal.ZERO;
        List<ItemLineSnapshot> snapshots = new ArrayList<>();

        if (items != null) {
            for (BookingCartItem item : items) {
                int qty = (item.getQuantity() != null && item.getQuantity() > 0) ? item.getQuantity() : 1;
                BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
                BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
                subtotal = subtotal.add(lineTotal);

                String serviceName = item.getService() != null ? item.getService().getName() : "Home Service";
                String durationLabel = item.getDuration() != null
                        ? (item.getDuration().getLabel() != null ? item.getDuration().getLabel() : item.getDuration().getMinutes() + " mins")
                        : null;
                String packageName = item.getServicePackage() != null ? item.getServicePackage().getName() : null;
                String subServiceName = item.getSubService() != null ? item.getSubService().getName() : null;

                snapshots.add(ItemLineSnapshot.builder()
                        .cartItemId(item.getCartItemId())
                        .serviceId(item.getService() != null ? item.getService().getServiceId() : null)
                        .serviceName(serviceName)
                        .durationId(item.getDuration() != null ? item.getDuration().getDurationId() : null)
                        .durationLabel(durationLabel)
                        .packageId(item.getServicePackage() != null ? item.getServicePackage().getPackageId() : null)
                        .packageName(packageName)
                        .subServiceId(item.getSubService() != null ? item.getSubService().getSubServiceId() : null)
                        .subServiceName(subServiceName)
                        .quantity(qty)
                        .unitPrice(unitPrice)
                        .totalPrice(lineTotal)
                        .build());
            }
        }

        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal additionalCharges = subtotal.compareTo(BigDecimal.ZERO) > 0 ? STANDARD_CONVENIENCE_FEE : BigDecimal.ZERO;

        // Taxes: 18% GST on (subtotal + additionalCharges)
        BigDecimal taxableAmount = subtotal.add(additionalCharges);
        BigDecimal taxAmount = taxableAmount.multiply(GST_RATE).setScale(2, RoundingMode.HALF_UP);

        // Coupon calculation
        BigDecimal discountAmount = BigDecimal.ZERO;
        Coupon appliedCoupon = null;
        String normalizedCode = (rawCouponCode != null && !rawCouponCode.trim().isEmpty())
                ? rawCouponCode.trim().toUpperCase()
                : null;

        if (normalizedCode != null && subtotal.compareTo(BigDecimal.ZERO) > 0) {
            Optional<Coupon> opt = couponRepository.findByCodeAndIsActiveTrue(normalizedCode);
            if (opt.isPresent()) {
                Coupon c = opt.get();
                Instant now = Instant.now();
                boolean isTimeValid = (c.getValidFrom() == null || !now.isBefore(c.getValidFrom()))
                        && (c.getValidUntil() == null || !now.isAfter(c.getValidUntil()));
                boolean isUsageValid = (c.getUsageLimit() == null || c.getTimesUsed() == null || c.getTimesUsed() < c.getUsageLimit());
                boolean isMinOrderMet = (c.getMinOrderValue() == null || subtotal.compareTo(c.getMinOrderValue()) >= 0);

                if (isTimeValid && isUsageValid && isMinOrderMet) {
                    appliedCoupon = c;
                    if (c.getDiscountType() == DiscountModel.PERCENTAGE) {
                        BigDecimal pct = c.getDiscountVal() != null ? c.getDiscountVal() : BigDecimal.ZERO;
                        discountAmount = subtotal.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                        if (c.getMaxDiscountAmount() != null && discountAmount.compareTo(c.getMaxDiscountAmount()) > 0) {
                            discountAmount = c.getMaxDiscountAmount();
                        }
                    } else { // FIXED_AMOUNT
                        discountAmount = c.getDiscountVal() != null ? c.getDiscountVal() : BigDecimal.ZERO;
                    }

                    if (discountAmount.compareTo(subtotal) > 0) {
                        discountAmount = subtotal;
                    }
                }
            }
        }

        discountAmount = discountAmount.setScale(2, RoundingMode.HALF_UP);

        // Final Payable Amount = subtotal + additionalCharges + taxAmount - discountAmount
        BigDecimal finalPayable = subtotal.add(additionalCharges).add(taxAmount).subtract(discountAmount);
        if (finalPayable.compareTo(BigDecimal.ZERO) < 0) {
            finalPayable = BigDecimal.ZERO;
        }
        finalPayable = finalPayable.setScale(2, RoundingMode.HALF_UP);

        // Generate JSON Snapshot
        Map<String, Object> breakdownMap = new LinkedHashMap<>();
        breakdownMap.put("subtotal", subtotal);
        breakdownMap.put("additionalCharges", additionalCharges);
        breakdownMap.put("taxRate", "18% GST");
        breakdownMap.put("taxAmount", taxAmount);
        breakdownMap.put("discountAmount", discountAmount);
        breakdownMap.put("couponCode", appliedCoupon != null ? appliedCoupon.getCode() : null);
        breakdownMap.put("finalPayableAmount", finalPayable);
        breakdownMap.put("items", snapshots);

        String breakdownJson = "{}";
        try {
            breakdownJson = objectMapper.writeValueAsString(breakdownMap);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize price breakdown JSON", e);
        }

        return PriceBreakdown.builder()
                .subtotal(subtotal)
                .additionalCharges(additionalCharges)
                .taxAmount(taxAmount)
                .discountAmount(discountAmount)
                .finalPayableAmount(finalPayable)
                .couponCode(appliedCoupon != null ? appliedCoupon.getCode() : null)
                .appliedCoupon(appliedCoupon)
                .breakdownJson(breakdownJson)
                .itemSnapshots(snapshots)
                .build();
    }
}

