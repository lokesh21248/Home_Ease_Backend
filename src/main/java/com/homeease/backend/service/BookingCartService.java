package com.homeease.backend.service;

import com.homeease.backend.dto.CartDto.*;
import com.homeease.backend.exception.BadRequestException;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class BookingCartService {

    private static final Logger log = LoggerFactory.getLogger(BookingCartService.class);

    private final BookingCartRepository cartRepository;
    private final BookingCartItemRepository cartItemRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;
    private final ServiceDurationRepository durationRepository;
    private final ServicePackageRepository packageRepository;
    private final SubServiceRepository subServiceRepository;
    private final PricingEngineService pricingEngineService;

    public BookingCartService(
            BookingCartRepository cartRepository,
            BookingCartItemRepository cartItemRepository,
            UserRepository userRepository,
            ServiceRepository serviceRepository,
            ServiceDurationRepository durationRepository,
            ServicePackageRepository packageRepository,
            SubServiceRepository subServiceRepository,
            PricingEngineService pricingEngineService) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.userRepository = userRepository;
        this.serviceRepository = serviceRepository;
        this.durationRepository = durationRepository;
        this.packageRepository = packageRepository;
        this.subServiceRepository = subServiceRepository;
        this.pricingEngineService = pricingEngineService;
    }

    /**
     * Get or create a cart for the specified user
     */
    public BookingCart getOrCreateCartEntity(UUID userId) {
        return cartRepository.findByUser_UserId(userId).orElseGet(() -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
            BookingCart newCart = BookingCart.builder()
                    .user(user)
                    .items(new ArrayList<>())
                    .build();
            return cartRepository.save(newCart);
        });
    }

    /**
     * Add item to user's cart (server-authoritative pricing)
     */
    public CartResponse addToCart(UUID userId, AddToCartRequest req) {
        BookingCart cart = getOrCreateCartEntity(userId);

        ServiceEntity service = serviceRepository.findById(req.getServiceId())
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + req.getServiceId()));

        if (Boolean.FALSE.equals(service.getIsActive())) {
            throw new BadRequestException("Service is currently unavailable.");
        }

        ServiceDuration duration = null;
        ServicePackage servicePackage = null;
        SubService subService = null;
        BigDecimal unitPrice;
        BigDecimal originalPrice = null;

        // Determine price securely from server DB
        if (req.getDurationId() != null) {
            duration = durationRepository.findById(req.getDurationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Duration not found: " + req.getDurationId()));
            if (!duration.getService().getServiceId().equals(service.getServiceId())) {
                throw new BadRequestException("Duration does not belong to the specified service.");
            }
            if (Boolean.FALSE.equals(duration.getIsActive())) {
                throw new BadRequestException("Selected duration is not active.");
            }
            unitPrice = duration.getPrice();
            originalPrice = duration.getOriginalPrice();
        } else if (req.getPackageId() != null) {
            servicePackage = packageRepository.findById(req.getPackageId())
                    .orElseThrow(() -> new ResourceNotFoundException("Package not found: " + req.getPackageId()));
            if (!servicePackage.getService().getServiceId().equals(service.getServiceId())) {
                throw new BadRequestException("Package does not belong to the specified service.");
            }
            if (Boolean.FALSE.equals(servicePackage.getIsActive())) {
                throw new BadRequestException("Selected package is not active.");
            }
            unitPrice = servicePackage.getPrice();
            originalPrice = servicePackage.getOriginalPrice();
        } else if (req.getSubServiceId() != null) {
            subService = subServiceRepository.findById(req.getSubServiceId())
                    .orElseThrow(() -> new ResourceNotFoundException("Sub-service not found: " + req.getSubServiceId()));
            if (!subService.getService().getServiceId().equals(service.getServiceId())) {
                throw new BadRequestException("Sub-service does not belong to the specified service.");
            }
            unitPrice = subService.getBasePrice();
        } else {
            unitPrice = service.getStartingPrice() != null ? service.getStartingPrice() : new BigDecimal("99.00");
        }

        int addQty = (req.getQuantity() != null && req.getQuantity() > 0) ? req.getQuantity() : 1;

        // Check if item already exists in cart to increment quantity
        Optional<BookingCartItem> existingItemOpt;
        if (duration != null) {
            existingItemOpt = cartItemRepository.findByCart_CartIdAndService_ServiceIdAndDuration_DurationId(
                    cart.getCartId(), service.getServiceId(), duration.getDurationId());
        } else if (servicePackage != null) {
            existingItemOpt = cartItemRepository.findByCart_CartIdAndService_ServiceIdAndServicePackage_PackageId(
                    cart.getCartId(), service.getServiceId(), servicePackage.getPackageId());
        } else if (subService != null) {
            existingItemOpt = cartItemRepository.findByCart_CartIdAndService_ServiceIdAndSubService_SubServiceId(
                    cart.getCartId(), service.getServiceId(), subService.getSubServiceId());
        } else {
            existingItemOpt = cartItemRepository.findByCart_CartId(cart.getCartId()).stream()
                    .filter(i -> i.getService().getServiceId().equals(service.getServiceId())
                            && i.getDuration() == null && i.getServicePackage() == null && i.getSubService() == null)
                    .findFirst();
        }

        if (existingItemOpt.isPresent()) {
            BookingCartItem existing = existingItemOpt.get();
            existing.setQuantity(existing.getQuantity() + addQty);
            existing.setUnitPrice(unitPrice); // Refresh price to latest DB price
            existing.setOriginalPrice(originalPrice);
            if (req.getNotes() != null) {
                existing.setNotes(req.getNotes());
            }
            cartItemRepository.save(existing);
        } else {
            BookingCartItem newItem = BookingCartItem.builder()
                    .cart(cart)
                    .service(service)
                    .duration(duration)
                    .servicePackage(servicePackage)
                    .subService(subService)
                    .quantity(addQty)
                    .unitPrice(unitPrice)
                    .originalPrice(originalPrice)
                    .notes(req.getNotes())
                    .build();
            cartItemRepository.save(newItem);
        }

        return getCart(userId, null);
    }

    /**
     * Get active cart with item list and computed pricing
     */
    @Transactional(readOnly = true)
    public CartResponse getCart(UUID userId, String couponCode) {
        BookingCart cart = cartRepository.findByUser_UserId(userId).orElse(null);
        if (cart == null) {
            return CartResponse.builder()
                    .userId(userId)
                    .items(Collections.emptyList())
                    .totalQuantity(0)
                    .subtotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .additionalCharges(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .taxAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .discountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .finalPayableAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .build();
        }

        List<BookingCartItem> items = cartItemRepository.findByCart_CartId(cart.getCartId());
        PricingEngineService.PriceBreakdown pricing = pricingEngineService.calculateCartPricing(items, couponCode);

        List<CartItemResponse> itemResponses = items.stream().map(this::mapItemToResponse).collect(Collectors.toList());
        int totalQty = items.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();

        return CartResponse.builder()
                .cartId(cart.getCartId())
                .userId(userId)
                .items(itemResponses)
                .totalQuantity(totalQty)
                .subtotal(pricing.getSubtotal())
                .additionalCharges(pricing.getAdditionalCharges())
                .taxAmount(pricing.getTaxAmount())
                .discountAmount(pricing.getDiscountAmount())
                .finalPayableAmount(pricing.getFinalPayableAmount())
                .couponCode(pricing.getCouponCode())
                .build();
    }

    /**
     * Update item quantity or notes
     */
    public CartResponse updateCartItem(UUID userId, UUID cartItemId, UpdateCartItemRequest req) {
        BookingCart cart = cartRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user: " + userId));

        BookingCartItem item = cartItemRepository.findByCart_CartIdAndCartItemId(cart.getCartId(), cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + cartItemId));

        if (req.getQuantity() != null) {
            if (req.getQuantity() <= 0) {
                cartItemRepository.delete(item);
            } else {
                item.setQuantity(req.getQuantity());
                if (req.getNotes() != null) {
                    item.setNotes(req.getNotes());
                }
                cartItemRepository.save(item);
            }
        } else if (req.getNotes() != null) {
            item.setNotes(req.getNotes());
            cartItemRepository.save(item);
        }

        return getCart(userId, null);
    }

    /**
     * Remove single item from cart
     */
    public CartResponse removeCartItem(UUID userId, UUID cartItemId) {
        BookingCart cart = cartRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user: " + userId));

        BookingCartItem item = cartItemRepository.findByCart_CartIdAndCartItemId(cart.getCartId(), cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + cartItemId));

        cartItemRepository.delete(item);
        return getCart(userId, null);
    }

    /**
     * Clear all items from user's cart
     */
    public void clearCart(UUID userId) {
        Optional<BookingCart> cartOpt = cartRepository.findByUser_UserId(userId);
        if (cartOpt.isPresent()) {
            cartItemRepository.deleteByCart_CartId(cartOpt.get().getCartId());
        }
    }

    /**
     * Lightweight summary for cart badge
     */
    @Transactional(readOnly = true)
    public CartSummaryResponse getCartSummary(UUID userId, String couponCode) {
        BookingCart cart = cartRepository.findByUser_UserId(userId).orElse(null);
        if (cart == null) {
            return CartSummaryResponse.builder()
                    .itemCount(0)
                    .subtotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .taxAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .discountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .finalPayableAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                    .build();
        }

        List<BookingCartItem> items = cartItemRepository.findByCart_CartId(cart.getCartId());
        PricingEngineService.PriceBreakdown pricing = pricingEngineService.calculateCartPricing(items, couponCode);
        int totalQty = items.stream().mapToInt(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();

        return CartSummaryResponse.builder()
                .itemCount(totalQty)
                .subtotal(pricing.getSubtotal())
                .taxAmount(pricing.getTaxAmount())
                .discountAmount(pricing.getDiscountAmount())
                .finalPayableAmount(pricing.getFinalPayableAmount())
                .build();
    }

    private CartItemResponse mapItemToResponse(BookingCartItem item) {
        int qty = item.getQuantity() != null ? item.getQuantity() : 1;
        BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        BigDecimal itemTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);

        String imageUrl = null;
        if (item.getService() != null) {
            imageUrl = item.getService().getImageUrl();
        }

        return CartItemResponse.builder()
                .cartItemId(item.getCartItemId())
                .serviceId(item.getService() != null ? item.getService().getServiceId() : null)
                .serviceName(item.getService() != null ? item.getService().getName() : null)
                .durationId(item.getDuration() != null ? item.getDuration().getDurationId() : null)
                .durationLabel(item.getDuration() != null ? item.getDuration().getLabel() : null)
                .durationMinutes(item.getDuration() != null ? item.getDuration().getMinutes() : null)
                .packageId(item.getServicePackage() != null ? item.getServicePackage().getPackageId() : null)
                .packageName(item.getServicePackage() != null ? item.getServicePackage().getName() : null)
                .subServiceId(item.getSubService() != null ? item.getSubService().getSubServiceId() : null)
                .subServiceName(item.getSubService() != null ? item.getSubService().getName() : null)
                .quantity(qty)
                .unitPrice(unitPrice)
                .originalPrice(item.getOriginalPrice())
                .itemTotal(itemTotal)
                .imageUrl(imageUrl)
                .notes(item.getNotes())
                .build();
    }
}

