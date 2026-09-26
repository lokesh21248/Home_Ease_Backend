package com.homeease.backend.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class WorkerServiceId implements Serializable {

    @Column(name = "worker_id")
    private UUID workerId;

    @Column(name = "sub_service_id")
    private UUID subServiceId;
}
