# Runs procedure-service with its Dapr sidecar.
# Prereqs: docker compose up -d, dapr init, sclera-common installed in ~/.m2
$env:SCLERA_DAPR_HEALTH_ENABLED = "true"
dapr run `
    --app-id sclera-procedure-service `
    --app-port 8095 `
    --dapr-http-port 3500 `
    --dapr-grpc-port 50001 `
    -- mvn -f sclera-procedure-service/pom.xml spring-boot:run
