# Runs the calendar service (per-property date/time rules: settings, working
# hours, holidays, downtime windows + availability queries) on :8098.
# Prereqs: docker compose up -d (Postgres sclera_calendar on :5432, Keycloak on
# :8180, OpenFGA on :8085), setup-keycloak.ps1, setup-openfga.ps1.
#
# Readiness: http://localhost:8098/actuator/health/readiness
# Swagger:   http://localhost:8098/swagger-ui/index.html
java -jar sclera-calendar-service/target/sclera-calendar-service-0.1.0-SNAPSHOT.jar
