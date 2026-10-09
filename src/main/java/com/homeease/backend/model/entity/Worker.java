package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.locationtech.jts.geom.Point;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "worker_id", updatable = false, nullable = false)
    private UUID workerId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "avatar_url", columnDefinition = "TEXT")
    private String avatarUrl;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "dob")
    private java.time.LocalDate dob;

    @Column(name = "experience_years")
    @Builder.Default
    private Integer experienceYears = 1;

    @Column(name = "pan_number", length = 20)
    private String panNumber;

    @Column(name = "pan_doc_url", columnDefinition = "TEXT")
    private String panDocUrl;

    @Column(name = "aadhaar_number", length = 20)
    private String aadhaarNumber;

    @Column(name = "aadhaar_doc_url", columnDefinition = "TEXT")
    private String aadhaarDocUrl;

    @Column(name = "bank_account_no", length = 30)
    private String bankAccountNo;

    @Column(name = "bank_ifsc", length = 20)
    private String bankIfsc;

    @Column(name = "kyc_status", length = 20)
    @Builder.Default
    private String kycStatus = "NOT_UPLOADED"; // NOT_UPLOADED, PENDING, APPROVED, REJECTED

    @Column(name = "is_online", nullable = false)
    @Builder.Default
    private Boolean isOnline = false;

    @Column(name = "current_lat")
    private Double currentLat;

    @Column(name = "current_lng")
    private Double currentLng;

    @Transient
    private Point location;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private Boolean isVerified = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
