// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Sage Kummer
// Clean-room reimplementation of the public WorldGuard 7 API for interoperability.
// Not derived from WorldGuard source code.

package com.sk89q.worldguard.protection.flags;

/**
 * The current name for {@link InvalidFlagFormat}, which it extends so either spelling catches both.
 * Flags here throw the parent, so a consumer catching this one only sees what it threw itself.
 */
public class InvalidFlagFormatException extends InvalidFlagFormat {

    public InvalidFlagFormatException(final String msg) {
        super(msg);
    }
}
