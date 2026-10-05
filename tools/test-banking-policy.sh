#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
banking_test_dir=$(mktemp -d)
trap 'rm -rf "$banking_test_dir"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -d "$banking_test_dir" app/src/main/java/com/sejio/calorapp/BankProvider.java app/src/main/java/com/sejio/calorapp/BankBrowserMode.java tools/BankProviderTest.java tools/BankBrowserModeTest.java
java -cp "$banking_test_dir" com.sejio.calorapp.BankProviderTest
java -cp "$banking_test_dir" com.sejio.calorapp.BankBrowserModeTest
