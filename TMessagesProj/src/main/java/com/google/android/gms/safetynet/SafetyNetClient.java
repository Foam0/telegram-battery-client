package com.google.android.gms.safetynet;

import com.google.android.gms.tasks.Task;

/** Stub — SafetyNet removed in FOSS builds. */
public class SafetyNetClient {
    public Task<SafetyNetResponse> attest(byte[] nonce, String apiKey) {
        return new Task<SafetyNetResponse>() {};
    }
}
