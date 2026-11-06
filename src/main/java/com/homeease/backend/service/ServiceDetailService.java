package com.homeease.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.ServiceDetailDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ServiceDetailService {

    private static final Logger log = LoggerFactory.getLogger(ServiceDetailService.class);

    private final ServiceRepository serviceRepository;
    private final ServiceDurationRepository durationRepository;
    private final ServicePackageRepository packageRepository;
    private final ServiceFeatureRepository featureRepository;
    private final ServiceHowItWorksRepository howItWorksRepository;
    private final ServiceInstructionRepository instructionRepository;
    private final ServiceImageRepository imageRepository;
    private final SubServiceRepository subServiceRepository;
    private final ObjectMapper objectMapper;

    public ServiceDetailService(
            ServiceRepository serviceRepository,
            ServiceDurationRepository durationRepository,
            ServicePackageRepository packageRepository,
            ServiceFeatureRepository featureRepository,
            ServiceHowItWorksRepository howItWorksRepository,
            ServiceInstructionRepository instructionRepository,
            ServiceImageRepository imageRepository,
            SubServiceRepository subServiceRepository,
            ObjectMapper objectMapper) {
        this.serviceRepository = serviceRepository;
        this.durationRepository = durationRepository;
        this.packageRepository = packageRepository;
        this.featureRepository = featureRepository;
        this.howItWorksRepository = howItWorksRepository;
        this.instructionRepository = instructionRepository;
        this.imageRepository = imageRepository;
        this.subServiceRepository = subServiceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Get complete dynamic service detail by service ID (Pronto / Urban Company style)
     */
    public ServiceDetailResponse getServiceDetail(UUID serviceId) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found with id: " + serviceId));

        // 1. Durations
        List<ServiceDuration> durationEntities = durationRepository.findByService_ServiceIdAndIsActiveTrueOrderByDisplayOrderAscMinutesAsc(serviceId);
        List<DurationDto> durations = durationEntities.stream()
                .map(d -> DurationDto.builder()
                        .durationId(d.getDurationId())
                        .minutes(d.getMinutes())
                        .label(d.getLabel() != null ? d.getLabel() : (d.getMinutes() + " Mins"))
                        .price(d.getPrice())
                        .originalPrice(d.getOriginalPrice())
                        .isPopular(d.getIsPopular())
                        .active(d.getIsActive())
                        .displayOrder(d.getDisplayOrder())
                        .build())
                .collect(Collectors.toList());

        // 2. Packages
        List<ServicePackage> packageEntities = packageRepository.findByService_ServiceIdAndIsActiveTrueOrderByDisplayOrderAsc(serviceId);
        List<PackageDto> packages = packageEntities.stream()
                .map(p -> {
                    List<String> inclusions = parseInclusions(p.getInclusions());
                    return PackageDto.builder()
                            .packageId(p.getPackageId())
                            .name(p.getName())
                            .description(p.getDescription())
                            .price(p.getPrice())
                            .originalPrice(p.getOriginalPrice())
                            .durationMins(p.getDurationMins())
                            .inclusions(inclusions)
                            .active(p.getIsActive())
                            .displayOrder(p.getDisplayOrder())
                            .build();
                })
                .collect(Collectors.toList());

        // 3. Features & What's Included / Excluded
        List<ServiceFeature> featureEntities = featureRepository.findByService_ServiceIdOrderByDisplayOrderAsc(serviceId);
        List<FeatureDto> features = featureEntities.stream()
                .map(f -> FeatureDto.builder()
                        .featureId(f.getFeatureId())
                        .title(f.getTitle())
                        .description(f.getDescription())
                        .iconUrl(f.getIconUrl())
                        .isIncluded(f.getIsIncluded())
                        .displayOrder(f.getDisplayOrder())
                        .build())
                .collect(Collectors.toList());

        // 4. How It Works Steps
        List<ServiceHowItWorks> howItWorksEntities = howItWorksRepository.findByService_ServiceIdOrderByStepNumberAsc(serviceId);
        List<HowItWorksDto> howItWorks = howItWorksEntities.stream()
                .map(h -> HowItWorksDto.builder()
                        .stepId(h.getStepId())
                        .stepNumber(h.getStepNumber())
                        .title(h.getTitle())
                        .description(h.getDescription())
                        .iconUrl(h.getIconUrl())
                        .build())
                .collect(Collectors.toList());

        // 5. Instructions & Things to Know
        List<ServiceInstruction> instructionEntities = instructionRepository.findByService_ServiceIdOrderByDisplayOrderAsc(serviceId);
        List<InstructionDto> instructions = instructionEntities.stream()
                .map(i -> InstructionDto.builder()
                        .instructionId(i.getInstructionId())
                        .title(i.getTitle())
                        .description(i.getDescription())
                        .displayOrder(i.getDisplayOrder())
                        .build())
                .collect(Collectors.toList());

        // 6. Gallery / Banner Images
        List<ServiceImage> imageEntities = imageRepository.findByService_ServiceIdOrderByDisplayOrderAsc(serviceId);
        List<ImageDto> galleryImages = imageEntities.stream()
                .map(img -> ImageDto.builder()
                        .imageId(img.getImageId())
                        .imageUrl(img.getImageUrl())
                        .caption(img.getCaption())
                        .isBanner(img.getIsBanner())
                        .displayOrder(img.getDisplayOrder())
                        .build())
                .collect(Collectors.toList());

        // 7. Sub-Services (backward-compatibility)
        List<SubService> subServiceEntities = subServiceRepository.findByService_ServiceIdAndIsActiveTrue(serviceId);
        List<SubServiceResponseItem> subServices = subServiceEntities.stream()
                .map(sub -> SubServiceResponseItem.builder()
                        .subServiceId(sub.getSubServiceId())
                        .name(sub.getName())
                        .pricingType(sub.getPricingType() != null ? sub.getPricingType().name() : "FIXED")
                        .basePrice(sub.getBasePrice())
                        .unitLabel(sub.getUnitLabel())
                        .estimatedMins(sub.getEstimatedMins())
                        .imageUrl(sub.getImageUrl())
                        .isActive(sub.getIsActive())
                        .build())
                .collect(Collectors.toList());

        // 8. Starting Price calculation
        BigDecimal startingPrice = service.getStartingPrice();
        if (startingPrice == null || startingPrice.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal minDuration = durations.stream().map(DurationDto::getPrice).min(BigDecimal::compareTo).orElse(null);
            BigDecimal minSub = subServices.stream().map(SubServiceResponseItem::getBasePrice).min(BigDecimal::compareTo).orElse(null);
            if (minDuration != null && minSub != null) {
                startingPrice = minDuration.min(minSub);
            } else if (minDuration != null) {
                startingPrice = minDuration;
            } else if (minSub != null) {
                startingPrice = minSub;
            } else {
                startingPrice = new BigDecimal("99.00");
            }
        }

        // 9. Booking Types
        List<String> bookingTypes = Arrays.asList((service.getBookingTypes() != null ? service.getBookingTypes() : "INSTANT,SCHEDULED").split(","));

        // 10. Service Availability
        ServiceAvailabilityDto availability = ServiceAvailabilityDto.builder()
                .isAvailableNow(service.getIsActive())
                .nextAvailableSlot("Today, within 20 mins")
                .instantEtaMinutes(18)
                .build();

        return ServiceDetailResponse.builder()
                .serviceId(service.getServiceId())
                .serviceName(service.getName())
                .subtitle(service.getSubtitle() != null ? service.getSubtitle() : service.getDescription())
                .description(service.getDescription())
                .bannerImage(service.getBannerImage() != null ? service.getBannerImage() : service.getImageUrl())
                .rating(service.getRating() != null ? service.getRating() : new BigDecimal("4.8"))
                .totalReviews(service.getTotalReviews() != null ? service.getTotalReviews() : 120)
                .startingPrice(startingPrice)
                .bookingTypes(bookingTypes)
                .cancellationPolicy(service.getCancellationPolicy() != null
                        ? service.getCancellationPolicy()
                        : "Free cancellation up to 2 hours before scheduled slot.")
                .serviceAvailability(availability)
                .durations(durations)
                .packages(packages)
                .features(features)
                .howItWorks(howItWorks)
                .instructions(instructions)
                .galleryImages(galleryImages)
                .subServices(subServices)
                .build();
    }

    private List<String> parseInclusions(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse inclusions JSON: {}", json);
            return Collections.emptyList();
        }
    }
}

