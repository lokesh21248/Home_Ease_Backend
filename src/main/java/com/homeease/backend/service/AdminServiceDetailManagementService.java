package com.homeease.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeease.backend.dto.AdminServiceManagementDto.*;
import com.homeease.backend.dto.ServiceDetailDto.*;
import com.homeease.backend.exception.ResourceNotFoundException;
import com.homeease.backend.model.entity.*;
import com.homeease.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AdminServiceDetailManagementService {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceDetailManagementService.class);

    private final ServiceRepository serviceRepository;
    private final ServiceDurationRepository durationRepository;
    private final ServicePackageRepository packageRepository;
    private final ServiceFeatureRepository featureRepository;
    private final ServiceHowItWorksRepository howItWorksRepository;
    private final ServiceInstructionRepository instructionRepository;
    private final ServiceImageRepository imageRepository;
    private final ObjectMapper objectMapper;

    public AdminServiceDetailManagementService(
            ServiceRepository serviceRepository,
            ServiceDurationRepository durationRepository,
            ServicePackageRepository packageRepository,
            ServiceFeatureRepository featureRepository,
            ServiceHowItWorksRepository howItWorksRepository,
            ServiceInstructionRepository instructionRepository,
            ServiceImageRepository imageRepository,
            ObjectMapper objectMapper) {
        this.serviceRepository = serviceRepository;
        this.durationRepository = durationRepository;
        this.packageRepository = packageRepository;
        this.featureRepository = featureRepository;
        this.howItWorksRepository = howItWorksRepository;
        this.instructionRepository = instructionRepository;
        this.imageRepository = imageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Create new service master
     */
    public ServiceEntity createService(CreateServiceRequest req) {
        ServiceEntity service = ServiceEntity.builder()
                .name(req.getName())
                .subtitle(req.getSubtitle())
                .description(req.getDescription())
                .imageUrl(req.getImageUrl())
                .bannerImage(req.getBannerImage() != null ? req.getBannerImage() : req.getImageUrl())
                .rating(req.getRating() != null ? req.getRating() : new BigDecimal("4.8"))
                .totalReviews(req.getTotalReviews() != null ? req.getTotalReviews() : 0)
                .startingPrice(req.getStartingPrice() != null ? req.getStartingPrice() : new BigDecimal("99.00"))
                .cancellationPolicy(req.getCancellationPolicy() != null
                        ? req.getCancellationPolicy()
                        : "Free cancellation up to 2 hours before scheduled slot.")
                .bookingTypes(req.getBookingTypes() != null ? req.getBookingTypes() : "INSTANT,SCHEDULED")
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .build();
        return serviceRepository.save(service);
    }

    /**
     * Update service master
     */
    public ServiceEntity updateService(UUID serviceId, CreateServiceRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        if (req.getName() != null) service.setName(req.getName());
        if (req.getSubtitle() != null) service.setSubtitle(req.getSubtitle());
        if (req.getDescription() != null) service.setDescription(req.getDescription());
        if (req.getImageUrl() != null) service.setImageUrl(req.getImageUrl());
        if (req.getBannerImage() != null) service.setBannerImage(req.getBannerImage());
        if (req.getRating() != null) service.setRating(req.getRating());
        if (req.getTotalReviews() != null) service.setTotalReviews(req.getTotalReviews());
        if (req.getStartingPrice() != null) service.setStartingPrice(req.getStartingPrice());
        if (req.getCancellationPolicy() != null) service.setCancellationPolicy(req.getCancellationPolicy());
        if (req.getBookingTypes() != null) service.setBookingTypes(req.getBookingTypes());
        if (req.getIsActive() != null) service.setIsActive(req.getIsActive());

        return serviceRepository.save(service);
    }

    /**
     * Toggle service status
     */
    public ServiceEntity toggleStatus(UUID serviceId, Boolean isActive) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));
        service.setIsActive(isActive);
        return serviceRepository.save(service);
    }

    // --- Durations ---

    public DurationDto addDuration(UUID serviceId, CreateDurationRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        ServiceDuration d = ServiceDuration.builder()
                .service(service)
                .minutes(req.getMinutes())
                .label(req.getLabel() != null ? req.getLabel() : (req.getMinutes() + " Mins"))
                .price(req.getPrice())
                .originalPrice(req.getOriginalPrice())
                .isPopular(req.getIsPopular() != null ? req.getIsPopular() : false)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();
        d = durationRepository.save(d);

        return DurationDto.builder()
                .durationId(d.getDurationId())
                .minutes(d.getMinutes())
                .label(d.getLabel())
                .price(d.getPrice())
                .originalPrice(d.getOriginalPrice())
                .isPopular(d.getIsPopular())
                .active(d.getIsActive())
                .displayOrder(d.getDisplayOrder())
                .build();
    }

    public DurationDto updateDuration(UUID durationId, CreateDurationRequest req) {
        ServiceDuration d = durationRepository.findById(durationId)
                .orElseThrow(() -> new ResourceNotFoundException("Duration not found: " + durationId));

        if (req.getMinutes() != null) d.setMinutes(req.getMinutes());
        if (req.getLabel() != null) d.setLabel(req.getLabel());
        if (req.getPrice() != null) d.setPrice(req.getPrice());
        if (req.getOriginalPrice() != null) d.setOriginalPrice(req.getOriginalPrice());
        if (req.getIsPopular() != null) d.setIsPopular(req.getIsPopular());
        if (req.getIsActive() != null) d.setIsActive(req.getIsActive());
        if (req.getDisplayOrder() != null) d.setDisplayOrder(req.getDisplayOrder());

        d = durationRepository.save(d);

        return DurationDto.builder()
                .durationId(d.getDurationId())
                .minutes(d.getMinutes())
                .label(d.getLabel())
                .price(d.getPrice())
                .originalPrice(d.getOriginalPrice())
                .isPopular(d.getIsPopular())
                .active(d.getIsActive())
                .displayOrder(d.getDisplayOrder())
                .build();
    }

    public void deleteDuration(UUID durationId) {
        if (!durationRepository.existsById(durationId)) {
            throw new ResourceNotFoundException("Duration not found: " + durationId);
        }
        durationRepository.deleteById(durationId);
    }

    // --- Packages ---

    public PackageDto addPackage(UUID serviceId, CreatePackageRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        String inclusionsJson = "[]";
        if (req.getInclusions() != null && !req.getInclusions().isEmpty()) {
            try {
                inclusionsJson = objectMapper.writeValueAsString(req.getInclusions());
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize inclusions JSON", e);
            }
        }

        ServicePackage p = ServicePackage.builder()
                .service(service)
                .name(req.getName())
                .description(req.getDescription())
                .price(req.getPrice())
                .originalPrice(req.getOriginalPrice())
                .durationMins(req.getDurationMins() != null ? req.getDurationMins() : 60)
                .inclusions(inclusionsJson)
                .isActive(req.getIsActive() != null ? req.getIsActive() : true)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();
        p = packageRepository.save(p);

        return PackageDto.builder()
                .packageId(p.getPackageId())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getPrice())
                .originalPrice(p.getOriginalPrice())
                .durationMins(p.getDurationMins())
                .inclusions(req.getInclusions() != null ? req.getInclusions() : Collections.emptyList())
                .active(p.getIsActive())
                .displayOrder(p.getDisplayOrder())
                .build();
    }

    public void deletePackage(UUID packageId) {
        if (!packageRepository.existsById(packageId)) {
            throw new ResourceNotFoundException("Package not found: " + packageId);
        }
        packageRepository.deleteById(packageId);
    }

    // --- Features ---

    public FeatureDto addFeature(UUID serviceId, CreateFeatureRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        ServiceFeature f = ServiceFeature.builder()
                .service(service)
                .title(req.getTitle())
                .description(req.getDescription())
                .iconUrl(req.getIconUrl())
                .isIncluded(req.getIsIncluded() != null ? req.getIsIncluded() : true)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();
        f = featureRepository.save(f);

        return FeatureDto.builder()
                .featureId(f.getFeatureId())
                .title(f.getTitle())
                .description(f.getDescription())
                .iconUrl(f.getIconUrl())
                .isIncluded(f.getIsIncluded())
                .displayOrder(f.getDisplayOrder())
                .build();
    }

    public void deleteFeature(UUID featureId) {
        if (!featureRepository.existsById(featureId)) {
            throw new ResourceNotFoundException("Feature not found: " + featureId);
        }
        featureRepository.deleteById(featureId);
    }

    // --- How It Works ---

    public HowItWorksDto addHowItWorks(UUID serviceId, CreateHowItWorksRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        ServiceHowItWorks h = ServiceHowItWorks.builder()
                .service(service)
                .stepNumber(req.getStepNumber())
                .title(req.getTitle())
                .description(req.getDescription())
                .iconUrl(req.getIconUrl())
                .build();
        h = howItWorksRepository.save(h);

        return HowItWorksDto.builder()
                .stepId(h.getStepId())
                .stepNumber(h.getStepNumber())
                .title(h.getTitle())
                .description(h.getDescription())
                .iconUrl(h.getIconUrl())
                .build();
    }

    public void deleteHowItWorks(UUID stepId) {
        if (!howItWorksRepository.existsById(stepId)) {
            throw new ResourceNotFoundException("HowItWorks step not found: " + stepId);
        }
        howItWorksRepository.deleteById(stepId);
    }

    // --- Instructions ---

    public InstructionDto addInstruction(UUID serviceId, CreateInstructionRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        ServiceInstruction i = ServiceInstruction.builder()
                .service(service)
                .title(req.getTitle())
                .description(req.getDescription())
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();
        i = instructionRepository.save(i);

        return InstructionDto.builder()
                .instructionId(i.getInstructionId())
                .title(i.getTitle())
                .description(i.getDescription())
                .displayOrder(i.getDisplayOrder())
                .build();
    }

    public void deleteInstruction(UUID instructionId) {
        if (!instructionRepository.existsById(instructionId)) {
            throw new ResourceNotFoundException("Instruction not found: " + instructionId);
        }
        instructionRepository.deleteById(instructionId);
    }

    // --- Images ---

    public ImageDto addImage(UUID serviceId, CreateImageRequest req) {
        ServiceEntity service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service not found: " + serviceId));

        ServiceImage img = ServiceImage.builder()
                .service(service)
                .imageUrl(req.getImageUrl())
                .caption(req.getCaption())
                .isBanner(req.getIsBanner() != null ? req.getIsBanner() : false)
                .displayOrder(req.getDisplayOrder() != null ? req.getDisplayOrder() : 0)
                .build();
        img = imageRepository.save(img);

        return ImageDto.builder()
                .imageId(img.getImageId())
                .imageUrl(img.getImageUrl())
                .caption(img.getCaption())
                .isBanner(img.getIsBanner())
                .displayOrder(img.getDisplayOrder())
                .build();
    }

    public void deleteImage(UUID imageId) {
        if (!imageRepository.existsById(imageId)) {
            throw new ResourceNotFoundException("Image not found: " + imageId);
        }
        imageRepository.deleteById(imageId);
    }
}

