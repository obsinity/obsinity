#! /bin/bash

i=1
while true; do
  if ! taskset -c 0-7 mvn clean install \
        -Dsurefire.parallel=none \
        -Dsurefire.forkCount=1 \
        -Dsurefire.reuseForks=false \
        2>&1 | tee -a obsinity-torture.log; then
    echo "Failed at iteration: $i" | tee -a obsinity-torture.log
    break
  fi

  echo "Iteration: $i" | tee -a obsinity-torture.log
  i=$((i+1))
done

