#!/bin/bash
set -e

echo "Compiling benchmarks..."
mvn clean test-compile

echo "Building classpath..."
CP="target/classes:target/test-classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q -DincludeScope=test)"

echo "Running benchmarks..."
java -cp "$CP" org.openjdk.jmh.Main -f 1 -wi 3 -i 5
