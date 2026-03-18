package com.android.billingclient.api;

import java.util.List;

/** Stub: billing is disabled in FOSS builds. */
public interface PurchasesUpdatedListener {
    void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases);
}
