// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.editing;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.List;

/** The layer assignments of the organization (GP-036); row-level security keeps each to its own. */
@Repository
public interface LayerAssignments {

    @Query("from LayerAssignment a order by a.layer, a.assignedAt")
    List<LayerAssignment> all();

    @Query("from LayerAssignment a where a.layer = :layer order by a.assignedAt")
    List<LayerAssignment> ofLayer(String layer);

    @Query("from LayerAssignment a where lower(a.username) = lower(:username) order by a.layer")
    List<LayerAssignment> ofUsername(String username);

    @Insert
    void insert(LayerAssignment assignment);

    @Update
    void update(LayerAssignment assignment);

    @Delete
    void delete(LayerAssignment assignment);
}
