// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata;

import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The datasets; row-level security keeps each organization to its own. */
@Repository
public interface Datasets {

    @Query("from Dataset d order by d.createdAt, d.title")
    List<Dataset> all();

    @Find
    Optional<Dataset> byId(UUID id);

    @Query("select count(d) from Dataset d")
    long count();

    @Insert
    void insert(Dataset dataset);
}
