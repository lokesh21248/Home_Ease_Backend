package com.homeease.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "worker_services")
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
