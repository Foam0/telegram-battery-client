package com.google.android.gms.wallet;

import com.google.android.gms.tasks.Task;

/** Stub — Google Wallet removed in FOSS builds. */
public class PaymentsClient {
    public Task<Boolean> isReadyToPay(IsReadyToPayRequest request) {
        return new Task<Boolean>() {};
    }
    public Task<PaymentData> loadPaymentData(PaymentDataRequest request) {
        return new Task<PaymentData>() {};
    }
}
