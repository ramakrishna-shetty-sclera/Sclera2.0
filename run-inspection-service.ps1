# Runs inspection-service with its Dapr sidecar.
# Prereqs: docker compose up -d, dapr init, sclera-common installed in ~/.m2
$env:SCLERA_DAPR_HEALTH_ENABLED = "true"
dapr run `
    --app-id sclera-inspection-service `
    --app-port 8096 `
    --dapr-http-port 3501 `
    --dapr-grpc-port 50002 `
    -- mvn -f sclera-inspection-service/pom.xml spring-boot:run
