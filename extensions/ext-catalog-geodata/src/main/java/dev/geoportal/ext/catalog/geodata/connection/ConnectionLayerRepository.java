// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The tables of connections mapped as layers (GP-986); row-level security keeps each organization to its own. */
@Repository
public interface ConnectionLayerRepository {

    @Find
    Optional<ConnectionLayer> byDataset(UUID datasetId);

    @Query("from ConnectionLayer c order by c.datasetId")
    List<ConnectionLayer> all();

    @Insert
    void insert(ConnectionLayer layer);

    @Update
    void update(ConnectionLayer layer);

    @Delete
    void delete(ConnectionLayer layer);
}
