// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The data rules on external services (GP-309); row-level security keeps each organization to its own. */
@Repository
public interface ServiceDataRules {

    @Query("from ServiceDataRule r where r.serviceId = :service order by r.createdAt, r.id")
    List<ServiceDataRule> ofService(UUID service);

    @Find
    Optional<ServiceDataRule> byId(UUID id);

    @Insert
    void insert(ServiceDataRule rule);

    @Delete
    void delete(ServiceDataRule rule);
}
