MDCAT:=$(firstword $(shell which mdcat cat 2>/dev/null))

.PHONY: help
help:
	$(MDCAT) MAKE.md


.PHONY: build
build:
	./gradlew assemble

.PHONY: doc
doc:
	./gradlew dokka

.PHONY: test
test:
	./src/test/run.sh

.PHONY: clean
clean:
	rm -fr build .gradle
