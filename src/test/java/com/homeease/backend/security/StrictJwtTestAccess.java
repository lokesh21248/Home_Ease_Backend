package com.homeease.backend.security;

/** Test-only bridge to the package-private rollback switch of {@link VerifiedCaller}. */
public final class StrictJwtTestAccess {

    private StrictJwtTestAccess() {
    }

    public static void setStrict(boolean strict) {
        VerifiedCaller.setStrict(strict);
    }
}
