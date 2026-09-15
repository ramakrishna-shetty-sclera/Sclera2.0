# Runs the helper service (dummy in-memory Locations + Assets CRUD) on :8097.
# No Dapr sidecar, no database — it holds all data in memory.
# Prereqs: docker compose up -d (Keycloak on :8180), setup-keycloak.ps1.
#
# Readiness: http://localhost:8097/actuator/health/readiness
# Swagger:   http://localhost:8097/swagger-ui/index.html
java -jar sclera-helper-service/target/sclera-helper-service-0.1.0-SNAPSHOT.jar
