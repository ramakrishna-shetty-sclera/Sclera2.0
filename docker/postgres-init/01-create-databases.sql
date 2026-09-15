-- One database per service (database-per-service pattern)
CREATE DATABASE sclera_procedure OWNER sclera;
CREATE DATABASE sclera_inspection OWNER sclera;
CREATE DATABASE sclera_helper OWNER sclera;
-- OpenFGA relationship-tuple store (fine-grained authorization)
CREATE DATABASE sclera_openfga OWNER sclera;
