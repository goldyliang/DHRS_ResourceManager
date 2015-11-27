#!/bin/bash
PATTERN='^(?!.*xterm.*).*java.*HotelServer.*'"$1"'$'
ps -ef | grep -P $PATTERN | tr -s ' ' | cut -f2 -d' ' | xargs kill -9

