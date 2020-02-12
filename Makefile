MDCAT:=$(firstword $(shell which mdcat cat 2>/dev/null))

.PHONY: help
help:
	$(MDCAT) MAKE.md


.PHONY: build
build:
	./gradlew build -x test

.PHONY: doc
doc:
	rm -fr docs
	./gradlew dokka
	mkdir -p docs
	mv build/docs/phoenix-channel/* docs/
	mv build/docs/style.css docs/
	for line in $$(find ./docs -name '*.html'); do sed -i 's/..\/style.css/style.css/g' $${line##* .}; done

.PHONY: test
test:
	./src/test/run.sh

.PHONY: clean
clean:
	rm -fr build .gradle


.PHONY: publish
publish:
	gradle bintrayUpload
