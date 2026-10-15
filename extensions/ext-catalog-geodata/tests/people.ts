// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import type { Person } from '@mosaikit/testing';

/** The third person of the bench, who becomes an administrator of the organization when a test needs one. */
export const MANAGER: Person = {
  user: 'manager@example.test',
  password: 'Bench-manager-2026',
  name: 'Manager',
};
