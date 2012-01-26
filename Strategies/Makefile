## --- Local Configuration (Edit, if needed)
prefix = $(HOME)/JForex/Strategies

## --- Main Code (MODIFY, ONLY IF YOU KNOW WHAT YOU'RE DOING)
.PHONY: all sync
all: sync
sync:
	@for tgt in $(prefix)/*.java; do \
    src=$${tgt##*/}; \
    if [ "$${src}" -nt "$${tgt}" ]; then \
	echo "$${src} --> $${tgt}"; \
	cp -p $${src} $${tgt}; \
    fi; \
done
	@for tgt in $(prefix)/*.java; do \
    src=$${tgt##*/}; \
    if [ "$${tgt}" -nt "$${src}" ]; then \
	echo "$${src} <-- $${tgt}"; \
	cp -p $${tgt} $${src}; \
    fi; \
done
