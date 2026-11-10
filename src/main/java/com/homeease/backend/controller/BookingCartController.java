package com.homeease.backend.controller;

import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.dto.CartDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.User;
import com.homeease.backend.repository.UserRepository;
import com.homeease.backend.security.JwtService;
import com.homeease.backend.service.BookingCartService;
import com.homeease.backend.service.CartCheckoutService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/cart", "/api/cart"})
@CrossOrigin(origins = "*")
public class BookingCartController {

    private final BookingCartService cartService;
    private final CartCheckoutService cartCheckoutService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public BookingCartController(
            BookingCartService cartService,
            CartCheckoutService cartCheckoutService,
            UserRepository userRepository,
            JwtService jwtService) {
        this.cartService = cartService;
        this.cartCheckoutService = cartCheckoutService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    private UUID resolveUserId(String authHeader, String headerUserIdStr, UUID queryUserId) {
        return com.homeease.backend.security.SecurityUtils.resolveAuthenticatedUserId(queryUserId, headerUserIdStr);
    }

    /**
     * Add service or duration to server cart
     */
    @PostMapping("/items")
    public ResponseEntity<CartResponse> addToCart(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody AddToCartRequest req) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(cartService.addToCart(userId, req));
    }

    /**
     * Get active cart with itemized breakdown and calculated price
     */
    @GetMapping
    public ResponseEntity<CartResponse> getCart(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestParam(value = "coupon", required = false) String coupon) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(cartService.getCart(userId, coupon));
    }

    /**
     * Update quantity or notes of a cart item
     */
    @PutMapping("/items/{itemId}")
    public ResponseEntity<CartResponse> updateCartItem(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @PathVariable UUID itemId,
            @Valid @RequestBody UpdateCartItemRequest req) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(cartService.updateCartItem(userId, itemId, req));
    }

    /**
     * Delete an item from the cart
     */
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<CartResponse> removeCartItem(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @PathVariable UUID itemId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(cartService.removeCartItem(userId, itemId));
    }

    /**
     * Clear all items in the cart
     */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> clearCart(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        cartService.clearCart(userId);
        return ResponseEntity.ok(Map.of("message", "Cart cleared successfully"));
    }

    /**
     * Lightweight summary for badge
     */
    @GetMapping("/summary")
    public ResponseEntity<CartSummaryResponse> getCartSummary(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @RequestParam(value = "coupon", required = false) String coupon) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(cartService.getCartSummary(userId, coupon));
    }

    /**
     * Checkout from cart: converts cart items into an atomic Booking
     */
    @PostMapping("/checkout")
    public ResponseEntity<BookingResponse> checkout(
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "userId", required = false) UUID queryUserId,
            @Valid @RequestBody CheckoutFromCartRequest req) {
        UUID userId = resolveUserId(authHeader, headerUserId, queryUserId);
        return ResponseEntity.ok(cartCheckoutService.checkout(userId, req));
    }
}

