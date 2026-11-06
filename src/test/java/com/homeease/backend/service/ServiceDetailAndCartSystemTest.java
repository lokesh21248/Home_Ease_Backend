package com.homeease.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.BookingDto.BookingResponse;
import com.homeease.backend.dto.CartDto.*;
import com.homeease.backend.dto.ServiceDetailDto.ServiceDetailResponse;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.model.enums.*;
import com.homeease.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ServiceDetailAndCartSystemTest {

    @Mock private ServiceRepository serviceRepository;
    @Mock private ServiceDurationRepository durationRepository;
    @Mock private ServicePackageRepository packageRepository;
    @Mock private ServiceFeatureRepository featureRepository;
    @Mock private ServiceHowItWorksRepository howItWorksRepository;
    @Mock private ServiceInstructionRepository instructionRepository;
    @Mock private ServiceImageRepository imageRepository;
    @Mock private SubServiceRepository subServiceRepository;

    @Mock private BookingCartRepository cartRepository;
    @Mock private BookingCartItemRepository cartItemRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private BookingServiceItemRepository bookingServiceItemRepository;
    @Mock private BookingPricingDetailRepository pricingDetailRepository;
    @Mock private BookingStatusHistoryRepository statusHistoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private CouponRepository couponRepository;
    @Mock private ServiceAreaService serviceAreaService;
    @Mock private BookingAssignmentService assignmentService;
    @Mock private BookingDomainEventService domainEventService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private ServiceDetailService serviceDetailService;
    private PricingEngineService pricingEngineService;
    private BookingCartService cartService;
    private CartCheckoutService checkoutService;

    private UUID serviceId;
    private UUID durationId;
    private UUID userId;
    private ServiceEntity service;
    private ServiceDuration duration;
    private User user;

    @BeforeEach
    void setUp() {
        serviceDetailService = new ServiceDetailService(
                serviceRepository,
                durationRepository,
                packageRepository,
                featureRepository,
                howItWorksRepository,
                instructionRepository,
                imageRepository,
                subServiceRepository,
                objectMapper
        );

        pricingEngineService = new PricingEngineService(couponRepository, objectMapper);

        cartService = new BookingCartService(
                cartRepository,
                cartItemRepository,
                userRepository,
                serviceRepository,
                durationRepository,
                packageRepository,
                subServiceRepository,
                pricingEngineService
        );

        checkoutService = new CartCheckoutService(
                cartRepository,
                cartItemRepository,
                bookingRepository,
                bookingServiceItemRepository,
                pricingDetailRepository,
                statusHistoryRepository,
                userRepository,
                subServiceRepository,
                couponRepository,
                serviceAreaService,
                assignmentService,
                domainEventService,
                pricingEngineService
        );

        serviceId = UUID.randomUUID();
        durationId = UUID.randomUUID();
        userId = UUID.randomUUID();

        service = ServiceEntity.builder()
                .serviceId(serviceId)
                .name("Hourly Cleaning Service")
                .description("Professional home cleaning")
                .startingPrice(new BigDecimal("99.00"))
                .rating(new BigDecimal("4.9"))
                .totalReviews(1200)
                .bookingTypes("INSTANT,SCHEDULED")
                .isActive(true)
                .build();

        duration = ServiceDuration.builder()
                .durationId(durationId)
                .service(service)
                .minutes(60)
                .label("60 Mins")
                .price(new BigDecimal("199.00"))
                .originalPrice(new BigDecimal("299.00"))
                .isActive(true)
                .displayOrder(1)
                .build();

        user = User.builder()
                .userId(userId)
                .fullName("John Customer")
                .phoneNumber("+919876543210")
                .build();
    }

    @Test
    @DisplayName("Service Detail API returns dynamic durations, packages, and starting price")
    void testGetServiceDetail() {
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(durationRepository.findByService_ServiceIdAndIsActiveTrueOrderByDisplayOrderAscMinutesAsc(serviceId))
                .thenReturn(List.of(duration));
        when(packageRepository.findByService_ServiceIdAndIsActiveTrueOrderByDisplayOrderAsc(serviceId))
                .thenReturn(Collections.emptyList());
        when(featureRepository.findByService_ServiceIdOrderByDisplayOrderAsc(serviceId))
                .thenReturn(Collections.emptyList());
        when(howItWorksRepository.findByService_ServiceIdOrderByStepNumberAsc(serviceId))
                .thenReturn(Collections.emptyList());
        when(instructionRepository.findByService_ServiceIdOrderByDisplayOrderAsc(serviceId))
                .thenReturn(Collections.emptyList());
        when(imageRepository.findByService_ServiceIdOrderByDisplayOrderAsc(serviceId))
                .thenReturn(Collections.emptyList());
        when(subServiceRepository.findByService_ServiceIdAndIsActiveTrue(serviceId))
                .thenReturn(Collections.emptyList());

        ServiceDetailResponse response = serviceDetailService.getServiceDetail(serviceId);

        assertNotNull(response);
        assertEquals("Hourly Cleaning Service", response.getServiceName());
        assertEquals(1, response.getDurations().size());
        assertEquals(60, response.getDurations().get(0).getMinutes());
        assertEquals(new BigDecimal("199.00"), response.getDurations().get(0).getPrice());
        assertEquals(new BigDecimal("99.00"), response.getStartingPrice());
    }

    @Test
    @DisplayName("Pricing Engine computes 18% GST, platform fee, and coupon discount correctly")
    void testPricingEngineCalculation() {
        BookingCartItem item = BookingCartItem.builder()
                .cartItemId(UUID.randomUUID())
                .service(service)
                .duration(duration)
                .unitPrice(new BigDecimal("200.00"))
                .quantity(2)
                .build();

        Coupon coupon = Coupon.builder()
                .code("SAVE50")
                .discountType(DiscountModel.FIXED_AMOUNT)
                .discountVal(new BigDecimal("50.00"))
                .minOrderValue(new BigDecimal("200.00"))
                .validFrom(Instant.now().minus(1, ChronoUnit.DAYS))
                .validUntil(Instant.now().plus(1, ChronoUnit.DAYS))
                .isActive(true)
                .build();

        when(couponRepository.findByCodeAndIsActiveTrue("SAVE50")).thenReturn(Optional.of(coupon));

        PricingEngineService.PriceBreakdown result = pricingEngineService.calculateCartPricing(List.of(item), "SAVE50");

        assertNotNull(result);
        assertEquals(new BigDecimal("400.00"), result.getSubtotal()); // 200 * 2
        assertEquals(new BigDecimal("49.00"), result.getAdditionalCharges()); // Platform fee
        // Taxable = 400 + 49 = 449.00 * 0.18 = 80.82
        assertEquals(new BigDecimal("80.82"), result.getTaxAmount());
        assertEquals(new BigDecimal("50.00"), result.getDiscountAmount());
        // Final = 400 + 49 + 80.82 - 50 = 479.82
        assertEquals(new BigDecimal("479.82"), result.getFinalPayableAmount());
        assertNotNull(result.getBreakdownJson());
    }

    @Test
    @DisplayName("BookingCartService adds duration item securely using database price")
    void testAddToCart() {
        BookingCart cart = BookingCart.builder().cartId(UUID.randomUUID()).user(user).items(new ArrayList<>()).build();

        when(cartRepository.findByUser_UserId(userId)).thenReturn(Optional.of(cart));
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(service));
        when(durationRepository.findById(durationId)).thenReturn(Optional.of(duration));
        when(cartItemRepository.findByCart_CartIdAndService_ServiceIdAndDuration_DurationId(cart.getCartId(), serviceId, durationId))
                .thenReturn(Optional.empty());

        AddToCartRequest req = AddToCartRequest.builder()
                .serviceId(serviceId)
                .durationId(durationId)
                .quantity(1)
                .build();

        cartService.addToCart(userId, req);

        verify(cartItemRepository, times(1)).save(argThat(item ->
                item.getUnitPrice().compareTo(new BigDecimal("199.00")) == 0 &&
                item.getQuantity() == 1
        ));
    }

    @Test
    @DisplayName("Cart Checkout converts cart items into booking and records domain event")
    void testCartCheckout() {
        UUID cartId = UUID.randomUUID();
        BookingCart cart = BookingCart.builder().cartId(cartId).user(user).build();

        BookingCartItem item = BookingCartItem.builder()
                .cartItemId(UUID.randomUUID())
                .service(service)
                .duration(duration)
                .unitPrice(new BigDecimal("199.00"))
                .quantity(1)
                .build();

        when(cartRepository.findByUser_UserId(userId)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCart_CartId(cartId)).thenReturn(List.of(item));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CheckoutFromCartRequest checkoutReq = CheckoutFromCartRequest.builder()
                .bookingType("INSTANT")
                .userLat(12.9716)
                .userLng(77.5946)
                .address("123 Indiranagar, Bangalore")
                .pincode("560038")
                .paymentMethod(PaymentMode.UPI)
                .idempotencyKey(UUID.randomUUID().toString())
                .build();

        BookingResponse response = checkoutService.checkout(userId, checkoutReq);

        assertNotNull(response);
        assertEquals(BookingStage.REQUESTED, response.getStatus());
        assertEquals(BookingType.INSTANT, response.getBookingType());
        verify(pricingDetailRepository, times(1)).save(any(BookingPricingDetail.class));
        verify(statusHistoryRepository, times(1)).save(any(BookingStatusHistory.class));
        verify(domainEventService, times(1)).recordEvent(
                any(Booking.class), eq(BookingEventType.BOOKING_CREATED), isNull(),
                eq(BookingStage.REQUESTED), eq(BookingActorType.CUSTOMER), eq(userId),
                anyString(), isNull(), isNull()
        );
        verify(cartItemRepository, times(1)).deleteByCart_CartId(cartId);
    }
}

