#!/bin/bash
PATTERN='^(?!.*xterm.*).*java.*DHRS_Corba.DHRS_Server.*'"$1"'$'
ps -ef | grep -P $PATTERN | tr -s ' ' | cut -f2 -d' ' | xargs kill -9

