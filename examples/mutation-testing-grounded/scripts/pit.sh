#!/usr/bin/env bash
# Mutation testing of the Maven project in /workspace with PIT (pitest.org), on a copy in /tmp.
#   pit.sh fetch <modules> <classes> <threads>     with network: fills the Maven repository in /cache
#   pit.sh measure <modules> <classes> <threads>   without network: runs PIT and leaves /scratch/pit/<stem>.csv, the stem of a module being its name as a file name (stems)
#   pit.sh key <modules> <classes> <threads>       prints the key of the project in the current folder (see key)
#   pit.sh stems <modules> <classes> <threads>     prints each module and the file-name stem its files get
# <modules> is a comma list of Maven modules, <classes> a PIT glob of the classes to change and of the tests to run.
set -euo pipefail
mode=$1 modules=$2 classes=$3 threads=$4
if [ -z "$modules" ] || [ -z "$classes" ]; then
  echo "The package names no project of its own: name the Maven modules and the classes to measure," \
    "--input modules=<a comma list, each as mvn -pl takes it, such as core,service or :my-app>" \
    "--input classes=<a PIT glob of the classes to change and of the tests to run, such as com.example.*>" >&2
  exit 2
fi
# The name of a module is what mvn -pl takes: a folder (core, services/billing, .) or :artifactId. The files of a module
# (/scratch/pit/<stem>.csv, the kept result, the report folder) get a stem made of it: every character outside
# [A-Za-z0-9._-] becomes _, the root folder "." is "root", and a stem never starts with a dot. Two modules with one
# stem would share files, so that is refused.
stems=() seen=" "
for module in ${modules//,/ }; do
  stem=${module//[^A-Za-z0-9._-]/_}
  [ "$module" = . ] && stem=root
  stem=${stem/#./_}
  case "$seen" in *" $stem "*)
    echo "The modules '$module' and another one both give the file name '$stem': name each module once, in one form" >&2
    exit 2;;
  esac
  seen+="$stem "; stems+=("$stem")
done
if [ "$mode" = stems ]; then
  i=0; for module in ${modules//,/ }; do printf '%s\t%s\n' "$module" "${stems[i]}"; i=$((i + 1)); done; exit
fi
pit=1.30.2 junit5=1.2.3
script=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")
dockerfile=$(dirname "$script")/../images/maven/Dockerfile

# The folders Maven writes its build output to: a folder target next to a pom.xml, below the current folder, as
# NUL-ended paths from "./". A folder target anywhere else, such as a Java package below src, is source.
build_outputs() {
  find . -type d -name target -not -path '*/.git/*' -print0 | while IFS= read -r -d '' dir; do
    [ -f "$dir/../pom.xml" ] && printf '%s\0' "$dir"
  done
  return 0
}

# The key of a kept result: a digest of what decides it. That is every pom.xml, every file below a src folder and
# the .mvn folder of the project in the current folder, at any depth, so any Maven layout; this script, which holds
# PIT's version and settings; the Dockerfile of the image, which pins Maven and the JDK; and <classes>.
key() {
  [ -f "$dockerfile" ] || { echo "no Dockerfile of the image at $dockerfile" >&2; return 2; }
  local prune=() dir
  while IFS= read -r -d '' dir; do
    dir=${dir//[\\*?\[]/\\&}
    prune+=(-path "$dir" -o)
  done < <(build_outputs)
  [ ${#prune[@]} -eq 0 ] || prune=(\( "${prune[@]::${#prune[@]}-1}" \) -prune -o)
  { find . "${prune[@]}" -type f \( -name pom.xml -o -path '*/src/*' -o -path './.mvn/*' \) \
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
excludes=()
while IFS= read -r -d '' dir; do excludes+=("--exclude=$dir"); done < <(cd /workspace && build_outputs)
tar -C /workspace --exclude=.git --no-wildcards --anchored "${excludes[@]}" -cf - . | tar -C /tmp/src -xf -
cd /tmp/src
# The same sources, tools and classes give the same result: it is kept in the cache under their key. A command
# substitution runs without errexit, so it is set again: a key that missed a file could hand back another result.
kept=/cache/pit/$(set -e; key "$classes")
# PIT finds JUnit 5 tests only with its plugin, which must be a dependency of the Maven plugin; no command line can
# say that, so the pom of the copy gets it.
sed -i "/<pluginManagement>/,/<plugins>/ s|<plugins>|<plugins><plugin><groupId>org.pitest</groupId><artifactId>pitest-maven</artifactId><version>$pit</version><configuration><skipFailingTests>true</skipFailingTests></configuration><dependencies><dependency><groupId>org.pitest</groupId><artifactId>pitest-junit5-plugin</artifactId><version>$junit5</version></dependency></dependencies></plugin>|" pom.xml
grep -q pitest-junit5-plugin pom.xml || { echo "pom.xml has no <pluginManagement><plugins> to add PIT to" >&2; exit 2; }

"${mvn[@]}" -q -pl "$modules" -am install -DskipTests
i=-1
for module in ${modules//,/ }; do
  i=$((i + 1)); stem=${stems[i]}
  if [ -s "$kept/$stem.csv" ]; then
    echo "== PIT on $module: measured before on the same sources ($kept)"; cp "$kept/$stem.csv" /scratch/pit/; continue
  fi
  echo "== PIT on $module ($mode)"
  # "initialize" first: the project's Surefire argLine names a property that only that phase sets.
  # A test that fails in this container without any change (it needs a program the image lacks) is left out and named.
  "${mvn[@]}" -pl "$module" initialize "org.pitest:pitest-maven:$pit:mutationCoverage" \
      -DtargetClasses="$classes" -DtargetTests="$classes" -Dthreads="$threads" -DfailWhenNoMutations=false \
      -DtimestampedReports=false -DoutputFormats=CSV -DreportsDirectory="/tmp/pit/$stem" > /tmp/pit.log 2>&1 \
    || { tail -n 60 /tmp/pit.log; exit 1; }
  grep -E '^>> |Completed in' /tmp/pit.log || true
  grep -o 'testClass=[^,]*, name=.*did not pass without mutation' /tmp/pit.log | sed 's/^/LEFT OUT: /' || true
  if [ "$mode" = measure ]; then
    mkdir -p "$kept"; cp "/tmp/pit/$stem/mutations.csv" "$kept/$stem.csv"; cp "$kept/$stem.csv" /scratch/pit/
  fi
done
