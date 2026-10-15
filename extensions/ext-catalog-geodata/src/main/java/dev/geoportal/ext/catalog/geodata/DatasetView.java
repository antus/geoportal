// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import java.util.UUID;

/** A dataset as the API returns it, and as the demo catalog describes it (without an id). */
public record DatasetView(
        UUID id,
        String title,
        String kind,
        String format,
        String theme,
        String host,
        String access,
        String updated,
        String size,
        String owner,
        String description) {}
