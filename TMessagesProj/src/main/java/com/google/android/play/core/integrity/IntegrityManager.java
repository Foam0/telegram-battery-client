package com.google.android.play.core.integrity;

import com.google.android.gms.tasks.Task;

/** Stub — Play Integrity removed in FOSS builds. */
public class IntegrityManager {
    public Task<IntegrityTokenResponse> requestIntegrityToken(IntegrityTokenRequest request) {
        return new Task<IntegrityTokenResponse>() {};
    }
}
