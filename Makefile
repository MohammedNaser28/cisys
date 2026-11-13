.PHONY: all clean run test lint check build docs platform-start platform-stop reset frontend format format-check checkstyle setup full

all: clean lint run

setup: frontend
	@npm --prefix src/frontend run prepare

frontend:
	@if [ ! -f src/frontend/node_modules/.package-lock.json ] || \
		[ src/frontend/package.json -nt src/frontend/node_modules/.package-lock.json ] || \
		[ src/frontend/package-lock.json -nt src/frontend/node_modules/.package-lock.json ]; then \
		npm --prefix src/frontend ci; \
	fi

lint: frontend
	@npm --prefix src/frontend run lint

check: frontend
	@npm --prefix src/frontend run check

# Reformats Java (google-java-format) and the frontend (Prettier).
format: frontend
	@mvn -B spotless:apply && npm --prefix src/frontend run format

# Checks formatting of Java and the frontend; makes no changes, as CI does.
format-check: frontend
	@mvn -B spotless:check && npm --prefix src/frontend run format:check

# Runs the minimal Checkstyle rules (see checkstyle.xml).
checkstyle:
	@mvn -B checkstyle:check

build: frontend
	@npm --prefix src/frontend run build

clean:
	@mvn clean

# Runs the frontend and the backend together; stopping either way stops both.
# The shell waits on the backend so it can act on signals right away, and the
# backend keeps the terminal's input (fd 3) for the Quarkus console keys.
run: frontend
	@exec 3<&0; \
	npm --prefix src/frontend run dev </dev/null & \
	frontend_pid=$$!; \
	mvn quarkus:dev -Dmaven.test.skip=true -Dquarkus.test.continuous-testing=disabled <&3 3<&- & \
	backend_pid=$$!; \
	trap 'kill $$frontend_pid $$backend_pid 2>/dev/null; wait' EXIT INT TERM; \
	wait $$backend_pid

test: lint format-check checkstyle
	@mvn test

docs:
	@mvn -Pmanual-docs generate-resources

platform-start:
	@if command -v podman-compose >/dev/null 2>&1; then \
		podman-compose up -d; \
	elif command -v docker >/dev/null 2>&1; then \
		docker compose up -d; \
	else \
		echo "No container runtime found (podman-compose or docker). Skipping compose services."; \
	fi

# Removes the compose containers, pod and network that exist, quietly; with
# argument "volumes" the data volumes too. Sets "removed" when anything was removed.
define remove_platform
if command -v podman >/dev/null 2>&1; then runtime=podman; \
elif command -v docker >/dev/null 2>&1; then runtime=docker; \
else echo "No container runtime found (podman or docker)."; exit 0; fi; \
filter=label=com.docker.compose.project=cisys; removed=; \
ids=$$($$runtime ps -aq --filter $$filter); \
if [ -n "$$ids" ]; then $$runtime stop -t 30 $$ids >/dev/null 2>&1; $$runtime rm -f $$ids >/dev/null && removed=1; fi; \
if [ $$runtime = podman ] && podman pod exists pod_cisys; then podman pod rm -f pod_cisys >/dev/null && removed=1; fi; \
ids=$$($$runtime network ls -q --filter $$filter); \
if [ -n "$$ids" ]; then $$runtime network rm $$ids >/dev/null && removed=1; fi; \
if [ "$(1)" = volumes ]; then \
	ids=$$($$runtime volume ls -q --filter $$filter); \
	if [ -n "$$ids" ]; then $$runtime volume rm $$ids >/dev/null && removed=1; fi; \
fi
endef

platform-stop:
	@$(call remove_platform); \
	if [ -n "$$removed" ]; then echo "Platform stopped."; else echo "Platform is not running."; fi

# Deletes the local PostgreSQL and Keycloak data volumes, after confirmation.
reset:
	@printf 'This deletes the local PostgreSQL and Keycloak data. Continue? [y/N] '; \
	read -r answer || answer=; \
	case "$$answer" in \
		[yY]|[yY][eE][sS]) ;; \
		*) echo "Aborted. Nothing was deleted."; exit 0 ;; \
	esac; \
	$(call remove_platform,volumes); \
	if [ -n "$$removed" ]; then echo "Local PostgreSQL and Keycloak data deleted."; else echo "Nothing to reset."; fi

full: platform-start clean frontend lint test build docs run
