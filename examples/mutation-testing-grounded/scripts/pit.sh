#!/usr/bin/env bash
# Mutation testing of the Maven project in /workspace with PIT (pitest.org), on a copy in /tmp.
#   pit.sh fetch <modules> <classes> <threads>     with network: fills the Maven repository in /cache
#   pit.sh measure <modules> <classes> <threads>   without network: runs PIT and leaves /scratch/pit/<module>.csv
#   pit.sh key <modules> <classes> <threads>       prints the key of the project in the current folder (see key)
# <modules> is a comma list of Maven modules, <classes> a PIT glob of the classes to change and of the tests to run.
set -euo pipefail
mode=$1 modules=$2 classes=$3 threads=$4
if [ -z "$modules" ] || [ -z "$classes" ]; then
  echo "The package names no project of its own: name the Maven modules and the classes to measure," \
    "--input modules=<a comma list, each as mvn -pl takes it, such as core,service or :my-app>" \
    "--input classes=<a PIT glob of the classes to change and of the tests to run, such as com.example.*>" >&2
  exit 2
fi
pit=1.30.2 junit5=1.2.3
script=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")
dockerfile=$(dirname "$script")/../images/maven/Dockerfile

# The key of a kept result: a digest of what decides it. That is every pom.xml, every file below a src folder and
# the .mvn folder of the project in the current folder, at any depth, so any Maven layout; this script, which holds
# PIT's version and settings; the Dockerfile of the image, which pins Maven and the JDK; and <classes>.
key() {
  [ -f "$dockerfile" ] || { echo "no Dockerfile of the image at $dockerfile" >&2; return 2; }
  { find . -type f \( -name pom.xml -o -path '*/src/*' -o -path './.mvn/*' \) -not -path '*/target/*' \
      -not -path '*/.git/*' -print0 | LC_ALL=C sort -z | xargs -0 -r sha256sum
    sha256sum < "$script"
    sha256sum < "$dockerfile"
    printf '%s\n' "$1"
  } | sha256sum | cut -c1-16
}
if [ "$mode" = key ]; then key "$classes"; exit; fi

export MAVEN_OPTS=-Xmx768m
mvn=(mvn -B -ntp -Dmaven.repo.local=/cache/m2)
if [ "$mode" = measure ]; then mvn+=(-o); else classes=no.such.Class; fi

mkdir -p /tmp/src /scratch/pit
tar -C /workspace --exclude=.git --exclude=target -cf - . | tar -C /tmp/src -xf -
cd /tmp/src
# The same sources, tools and classes give the same result: it is kept in the cache under their key. A command
# substitution runs without errexit, so it is set again: a key that missed a file could hand back another result.
kept=/cache/pit/$(set -e; key "$classes")
# PIT finds JUnit 5 tests only with its plugin, which must be a dependency of the Maven plugin; no command line can
# say that, so the pom of the copy gets it.
sed -i "/<pluginManagement>/,/<plugins>/ s|<plugins>|<plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><version>$pit</version><configuration><skipFailingTests>true</skipFailingTests></configuration><dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>$junit5</version></dependency></dependencies></plugin>|" pom.xml
grep -q pitest-junit5-plugin pom.xml || { echo "pom.xml has no <pluginManagement><plugins> to add PIT to" >&2; exit 2; }

"${mvn[@]}" -q -pl "$modules" -am install -DskipTests
for module in ${modules//,/ }; do
  if [ -s "$kept/$module.csv" ]; then
    echo "== PIT on $module: measured before on the same sources ($kept)"; cp "$kept/$module.csv" /scratch/pit/; continue
  fi
  echo "== PIT on $module ($mode)"
  # "initialize" first: the project's Surefire argLine names a property that only that phase sets.
  # A test that fails in this container without any change (it needs a program the image lacks) is left out and named.
  "${mvn[@]}" -pl "$module" initialize "org.pitest:pitest-maven:$pit:mutationCoverage" \
      -DtargetClasses="$classes" -DtargetTests="$classes" -Dthreads="$threads" -DfailWhenNoMutations=false \
      -DtimestampedReports=false -DoutputFormats=CSV -DreportsDirectory="/tmp/pit/$module" > /tmp/pit.log 2>&1 \
    || { tail -n 60 /tmp/pit.log; exit 1; }
  grep -E '^>> |Completed in' /tmp/pit.log || true
  grep -o 'testClass=[^,]*, name=.*did not pass without mutation' /tmp/pit.log | sed 's/^/LEFT OUT: /' || true
  if [ "$mode" = measure ]; then
    mkdir -p "$kept"; cp "/tmp/pit/$module/mutations.csv" "$kept/$module.csv"; cp "$kept/$module.csv" /scratch/pit/
  fi
done
