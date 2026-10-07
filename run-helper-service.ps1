# Runs helper-service with its Dapr sidecar.
# Locations and assets persist in Postgres (sclera_helper, schema per tenant),
# behind the same JWT + OpenFGA checks as the other services. It is the
# temporary stand-in for the external services; the sidecar is what lets the
# procedure service call it over Dapr invocation (app-id sclera-helper-service).
# Prereqs: docker compose up -d, dapr init, setup-keycloak.ps1, setup-openfga.ps1,
#          sclera-common installed in ~/.m2, and the jar built:
#   mvn -f sclera-helper-service/pom.xml package "-Dmaven.test.skip=true"
#
# Ports 3502 / 50003 keep it clear of the procedure (3500 / 50001) and
# inspection (3501 / 50002) sidecars on the same machine.
#
# Readiness: http://localhost:8097/actuator/health/readiness
# Swagger:   http://localhost:8097/swagger-ui/index.html
$env:SCLERA_DAPR_HEALTH_ENABLED = "true"
dapr run `
    --app-id sclera-helper-service `
    --app-port 8097 `
    --dapr-http-port 3502 `
    --dapr-grpc-port 50003 `
    -- java -jar sclera-helper-service/target/sclera-helper-service-0.1.0-SNAPSHOT.jar
