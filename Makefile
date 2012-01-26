## --- Local Configuration (Edit, if needed)
.SILENT:

## --- Main Code (MODIFY, ONLY IF YOU KNOW WHAT YOU'RE DOING)
all:
	cd Indicators && make
	cd Strategies && make
