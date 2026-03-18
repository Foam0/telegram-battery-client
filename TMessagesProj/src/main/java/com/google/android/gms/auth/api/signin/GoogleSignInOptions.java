package com.google.android.gms.auth.api.signin;

/** Stub — Google Sign-In removed in FOSS builds. */
public class GoogleSignInOptions {
    public static class Builder {
        public Builder requestIdToken(String clientId) { return this; }
        public Builder requestEmail() { return this; }
        public GoogleSignInOptions build() { return new GoogleSignInOptions(); }
    }
}
