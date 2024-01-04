#!/bin/bash

while true
do
   rsync -avz -e "ssh -i ~/.ssh/id_rsa" root@192.168.44.188:/unitech/basic-service-redis-6.0.5/data2/appendonly.aof ~/appendonly.aof
   sleep 10
done