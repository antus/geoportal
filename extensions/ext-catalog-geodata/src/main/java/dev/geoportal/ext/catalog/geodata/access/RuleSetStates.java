// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Update;
import java.util.List;

/** The rule set state of the organization; row-level security shows only its own row. */
@Repository
public interface RuleSetStates {

    @Query("from RuleSetState s")
    List<RuleSetState> mine();

    @Insert
    void insert(RuleSetState state);

    @Update
    void update(RuleSetState state);
}
