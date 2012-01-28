## --- Local Configuration (Edit, if needed)
.SILENT:

## --- Main Code (MODIFY, ONLY IF YOU KNOW WHAT YOU'RE DOING)
.PHONY: all ChangeLog
all:
	cd Indicators && $(MAKE)
	cd Strategies && $(MAKE)

ChangeLog:
	git log --graph >$@
