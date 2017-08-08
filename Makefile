.PHONY: all

DOCKER_IMAGE_TAG := haystack-stitch-span-collector

clean:
	mvn clean

build:  clean
	mvn package

docker_build:
	docker build -t $(DOCKER_IMAGE_TAG) -f build/Dockerfile .

.PHONY: integration_test
integration_test:
	mvn test -P integration-tests

all: build integration_test docker_build

# build all and release
.PHONY: release
REPO := lib/haystack-stitch-span-collector
BRANCH := $(shell git rev-parse --abbrev-ref HEAD)
ifeq ($(BRANCH), master)
release: all
	docker tag $(DOCKER_IMAGE_TAG) $(REPO):latest
	docker push $(REPO)
else
release: all
endif
