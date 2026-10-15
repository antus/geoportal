// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The data rules of the organization (GP-308); row-level security keeps each organization to its own. */
@Repository
public interface DataRules {

    @Query("from DataRule r order by r.createdAt, r.id")
    List<DataRule> all();

    @Query("from DataRule r where r.datasetId = :dataset order by r.createdAt, r.id")
    List<DataRule> ofDataset(UUID dataset);

    @Find
    Optional<DataRule> byId(UUID id);

    @Insert
    void insert(DataRule rule);

    @Update
    void update(DataRule rule);

    @Delete
    void delete(DataRule rule);
}
