#!/bin/sh
# The tool of the package combo-model-text: it notes that it ran and prints its arguments. No test expects it to run.
set -u
echo ran >> /scratch/tool-ran.txt
for argument in "$@"; do
  printf 'argument: %s\n' "$argument"
done
