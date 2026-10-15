// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The external services; row-level security keeps each organization to its own. */
@Repository
public interface ExternalServices {

    @Query("from ExternalService s order by s.title, s.createdAt")
    List<ExternalService> all();

    @Find
    Optional<ExternalService> byId(UUID id);

    @Insert
    void insert(ExternalService service);

    @Update
    void update(ExternalService service);

    @Delete
    void delete(ExternalService service);
}
