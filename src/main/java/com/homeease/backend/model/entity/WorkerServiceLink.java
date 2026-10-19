package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "worker_services", indexes = {
    @Index(name = "idx_worker_services_sub_service", columnList = "sub_service_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkerServiceLink {

    @EmbeddedId
    private WorkerServiceId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workerId")
    @JoinColumn(name = "worker_id")
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("subServiceId")
    @JoinColumn(name = "sub_service_id")
    private SubService subService;
}
