#!/bin/bash

#can be true/false to set thread pinning
explicitbind=$1
#can be core/socket/none
procbind=$2
nodes=16
name="$nodes"n
nodefile=nodes.$name.txt

#./run.generic.loop.sh 1 24 $nodefile $nodes 1 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
#./run.generic.loop.sh 2 12 $nodefile $nodes 2 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
#./run.generic.loop.sh 3 8 $nodefile $nodes 3 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
#./run.generic.loop.sh 4 6 $nodefile $nodes 4 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
#./run.generic.loop.sh 6 4 $nodefile $nodes 6 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
#./run.generic.loop.sh 8 3 $nodefile $nodes 8 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
#./run.generic.loop.sh 12 2 $nodefile $nodes 12 g $explicitbind $procbind
#list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
./run.generic.loop.sh 24 1 $nodefile $nodes 24 g $explicitbind $procbind
list.cmd.sh $nodefile "rm -rf /dev/shm/$USER/*.bin /dev/shm/*.bin"
