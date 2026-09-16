package com.yourname.inventory.supplier;

/** Transient failure of the (simulated) external supplier API - the kind of error worth retrying. */
public class SupplierUnavailableException extends RuntimeException {

    public SupplierUnavailableException(String message) {
        super(message);
    }
}
