.PHONY: build-jar build-native build-installer clean run

APP_NAME ?= xml-reader
MAIN_CLASS ?= com.example.Main

MVN ?= mvn
TARGET_DIR := target

# Maven Shade gera: target/<artifactId>-<version>-all.jar
VERSION := $(shell $(MVN) -q -DforceStdout help:evaluate -Dexpression=project.version)
ARTIFACT_ID := $(shell $(MVN) -q -DforceStdout help:evaluate -Dexpression=project.artifactId)
UBER_JAR := $(TARGET_DIR)/$(ARTIFACT_ID)-$(VERSION)-all.jar

OS := $(shell uname -s)

build-jar:
	$(MVN) -q -DskipTests package
	@echo "Uber-JAR gerado em: $(UBER_JAR)"

build-native:
	# Requer GraalVM + native-image instalados e JAVA_HOME apontando para GraalVM
	$(MVN) -q -DskipTests -Pnative package
	@echo "Binário nativo (Linux/macOS) geralmente em: $(TARGET_DIR)/$(ARTIFACT_ID)"
	@echo "Binário nativo (Windows) geralmente em: $(TARGET_DIR)/$(ARTIFACT_ID).exe"

build-installer: build-jar
	@mkdir -p $(TARGET_DIR)/jpackage-input $(TARGET_DIR)/installer
	@cp -f "$(UBER_JAR)" "$(TARGET_DIR)/jpackage-input/"
	@echo "Gerando instalador com jpackage..."
ifeq ($(OS),Linux)
	# Para gerar .deb, você precisa das ferramentas do sistema (dpkg-deb etc.)
	jpackage \
		--type deb \
		--dest "$(TARGET_DIR)/installer" \
		--name "$(APP_NAME)" \
		--input "$(TARGET_DIR)/jpackage-input" \
		--main-jar "$(notdir $(UBER_JAR))" \
		--main-class "$(MAIN_CLASS)" \
		--app-version "$(VERSION)" \
		--add-modules java.base,java.xml,java.logging
	@echo "Installer (.deb) em: $(TARGET_DIR)/installer"
else ifeq ($(OS),Darwin)
	jpackage \
		--type dmg \
		--dest "$(TARGET_DIR)/installer" \
		--name "$(APP_NAME)" \
		--input "$(TARGET_DIR)/jpackage-input" \
		--main-jar "$(notdir $(UBER_JAR))" \
		--main-class "$(MAIN_CLASS)" \
		--app-version "$(VERSION)" \
		--add-modules java.base,java.xml,java.logging
	@echo "Installer (.dmg) em: $(TARGET_DIR)/installer"
else
	# Windows (MSI/EXE) depende do WiX Toolset (MSI) ou do gerador EXE disponível no ambiente.
	jpackage \
		--type exe \
		--dest "$(TARGET_DIR)/installer" \
		--name "$(APP_NAME)" \
		--input "$(TARGET_DIR)/jpackage-input" \
		--main-jar "$(notdir $(UBER_JAR))" \
		--main-class "$(MAIN_CLASS)" \
		--app-version "$(VERSION)" \
		--add-modules java.base,java.xml,java.logging
	@echo "Installer (.exe) em: $(TARGET_DIR)/installer"
endif

clean:
	$(MVN) -q clean
	@rm -rf "$(TARGET_DIR)/jpackage-input" "$(TARGET_DIR)/installer"

run: build-jar
	java -jar "$(UBER_JAR)"
