.PHONY: setup build run dev-api dev-ui test browser-test
setup:
	./scripts/mvnw --version
	npm ci --prefix frontend --no-audit --no-fund
build:
	rm -rf backend/target/classes/static
	npm run build --prefix frontend
	./scripts/mvnw package -DskipTests -q
run:
	java -Xmx768m -jar backend/target/api-test-runner-1.0.0.jar
dev-api:
	./scripts/mvnw spring-boot:run
dev-ui:
	npm start --prefix frontend
test:
	./scripts/mvnw test -q
	npm run build --prefix frontend
browser-test:
	npm run test:e2e --prefix frontend
