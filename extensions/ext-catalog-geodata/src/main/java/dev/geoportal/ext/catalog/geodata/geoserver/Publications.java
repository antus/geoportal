// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.List;
import java.util.Optional;

/** The layers and styles the catalog publishes; row-level security keeps each organization to its own. */
@Repository
public interface Publications {

    @Query("from PublishedLayer l order by l.name")
    List<PublishedLayer> layers();

    @Find
    Optional<PublishedLayer> layer(String name);

    @Insert
    void insert(PublishedLayer layer);

    @Update
    void update(PublishedLayer layer);

    @Delete
    void delete(PublishedLayer layer);

    @Query("from PublishedStyle s order by s.name")
    List<PublishedStyle> styles();

    @Find
    Optional<PublishedStyle> style(String name);

    @Insert
    void insert(PublishedStyle style);

    @Update
    void update(PublishedStyle style);

    @Delete
    void delete(PublishedStyle style);
}
