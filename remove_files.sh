#!/bin/sh
# Run from the repo root after extracting the zip: deletes the files that were removed.
while read -r f; do git rm -f --ignore-unmatch "$f"; done < REMOVED_FILES.txt
