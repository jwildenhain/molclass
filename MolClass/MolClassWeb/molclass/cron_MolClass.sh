#!/bin/bash

echo "Fill MolClass MySQL memory tables!"
cd /var/www/molclass/
sudo rm /tmp/moldb_molcfp.txt
sudo rm /tmp/moldb_molstat.txt
perl tools/cp2mem.pl
echo "Start listener for structure searches!"
nohup /var/www/molclass/tools/cmmmsrv &