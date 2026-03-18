package com.google.android.gms.auth.api.signin;

import android.content.Intent;
import com.google.android.gms.tasks.Task;

/** Stub — Google Sign-In removed in FOSS builds. */
public class GoogleSignInClient {
    public Task<Void> signOut() { return new Task<Void>() {}; }
    public Intent getSignInIntent() { return new Intent(); }
}
