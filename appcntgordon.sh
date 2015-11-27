#!/bin/bash

ps -ef | grep -P '^(?!.*xterm.*).*java.*HotelServer.*$' | wc -l

