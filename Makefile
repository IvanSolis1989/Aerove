.PHONY: help check full-analyze lite-test

help:
	@echo 'make check         # validate workspace layout and document links'
	@echo 'make full-analyze  # analyze apps/aerove'
	@echo 'make lite-test     # test apps/aerove-lite'

check:
	python tooling/workspace.py check

full-analyze:
	python tooling/workspace.py run aerove -- flutter analyze --no-fatal-infos

lite-test:
	python tooling/workspace.py run aerove-lite -- gradle :app:testReleaseUnitTest
