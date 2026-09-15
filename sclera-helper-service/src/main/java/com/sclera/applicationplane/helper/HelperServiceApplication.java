package com.sclera.applicationplane.helper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Helper service — dummy, in-memory CRUD for Locations (building → floor →
 * location hierarchy) and Assets (IP / non-IP, optionally tagged to a
 * location). The inspection-service will consume these later.
 *
 * The "com.sclera.controlplane.common" scan entry is mandatory — without it the
 * shared beans (response envelope, filters, error handling) silently never load.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.sclera.applicationplane.helper",
                "com.sclera.controlplane.common"
        })
public class HelperServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelperServiceApplication.class, args);
    }
}
