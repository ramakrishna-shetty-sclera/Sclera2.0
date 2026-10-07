package com.sclera.applicationplane.procedure.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Who a procedure is for — a key {@code procedure_template.consumer_key} may
 * name, such as INSPECTION. An organization's vocabulary, held in its own
 * schema and seeded when the schema is provisioned.
 *
 * <p>Read-only: no setters, so nothing here can change a key once it is stored.
 * The key is the identity a template keeps, so it is never reused or renamed.
 */
@Entity
@Table(name = "procedure_consumer")
public class ProcedureConsumer {

    @Id
    @Column(nullable = false, updatable = false, length = 50)
    private String key;

    @Column(nullable = false, length = 100)
    private String name;

    /** False once retired: it may not be chosen for a new procedure, but existing ones keep it. */
    @Column(nullable = false)
    private boolean active = true;

    protected ProcedureConsumer() {
        // for JPA
    }

    public String getKey() { return key; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}