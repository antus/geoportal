// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.proxy;

/**
 * An address the proxy will not reach: not http or https, malformed, outside the registered service,
 * or resolving to an internal network. The message says why, in English, without the addresses the
 * name resolved to, so that an answer never maps the internal network for whoever asks.
 */
public final class RefusedTarget extends Exception {

    private static final long serialVersionUID = 1L;

    public RefusedTarget(String message) {
        super(message);
    }
}
