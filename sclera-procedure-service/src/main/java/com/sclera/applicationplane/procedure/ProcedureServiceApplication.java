package com.sclera.applicationplane.procedure;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Question-template (procedure) authoring service.
 *
 * The "com.sclera.controlplane.common" scan entry is mandatory — without it the
 * shared beans (response envelope, filters, error handling) silently never load.
 */
@SpringBootApplication(
        scanBasePackages = {
                "com.sclera.applicationplane.procedure",
                "com.sclera.controlplane.common"
        })
public class ProcedureServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcedureServiceApplication.class, args);
    }
}
