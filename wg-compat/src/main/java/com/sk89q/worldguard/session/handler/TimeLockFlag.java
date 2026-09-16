// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.session.handler;

import com.sk89q.worldguard.session.Session;

/**
 * Ordering anchor for the {@code time-lock} flag. Inert; see the package documentation.
 */
public class TimeLockFlag extends Handler {

    public static final Factory FACTORY = new Factory();

    protected TimeLockFlag(final Session session) {
        super(session);
    }

    public static class Factory extends Handler.Factory<TimeLockFlag> {
        @Override
        public TimeLockFlag create(final Session session) {
            return new TimeLockFlag(session);
        }
    }
}
