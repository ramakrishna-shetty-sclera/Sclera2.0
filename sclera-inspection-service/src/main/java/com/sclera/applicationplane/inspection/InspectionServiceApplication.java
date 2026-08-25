package com.sclera.applicationplane.inspection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inspection execution service — creates inspections from published question
 * templates (fetched from procedure-service over Dapr) and records answers.
 *
 * The "com.sclera.controlplane.common" scan entry is mandatory — without it the
 * shared beans (response envelope, filters, error handling) silently never load.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.sclera.applicationplane.inspection",
                "com.sclera.controlplane.common"
        })
public class InspectionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InspectionServiceApplication.class, args);
    }
}
