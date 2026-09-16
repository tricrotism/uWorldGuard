// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.session.handler;

import com.sk89q.worldguard.session.Session;

/**
 * Ordering anchor for WorldGuard's water-breathing handler. Inert, and the one anchor with no
 * uWorldGuard flag behind it; see the package documentation.
 */
public class WaterBreathing extends Handler {

    public static final Factory FACTORY = new Factory();

    protected WaterBreathing(final Session session) {
        super(session);
    }

    public static class Factory extends Handler.Factory<WaterBreathing> {
        @Override
        public WaterBreathing create(final Session session) {
            return new WaterBreathing(session);
        }
    }
}
