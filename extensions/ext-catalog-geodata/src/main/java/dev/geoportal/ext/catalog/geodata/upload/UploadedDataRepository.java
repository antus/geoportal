// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The uploaded data; row-level security keeps each organization to its own. */
@Repository
public interface UploadedDataRepository {

    @Query("from UploadedData u where u.id in :ids order by u.title, u.id")
    List<UploadedData> byIds(Collection<UUID> ids);

    @Find
    Optional<UploadedData> byId(UUID id);

    /** The dataset made from a file of the catalog (GP-983). */
    @Query("from UploadedData u where u.catalogFile = :file")
    Optional<UploadedData> byCatalogFile(UUID file);

    @Query("select u.technicalName from UploadedData u where u.technicalName like :prefix")
    List<String> technicalNamesLike(String prefix);

    @Insert
    void insert(UploadedData data);

    @Update
    void update(UploadedData data);

    @Delete
    void delete(UploadedData data);
}
