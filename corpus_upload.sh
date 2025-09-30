#!/bin/bash
# Copyright (c) Meta Platforms, Inc. and affiliates.

# REMOVE BEFORE OPEN-SOURCING

# This script is used to upload the data from your local machine to manifold.
# Ensure you have the manifold cli installed on your machine.
# https://www.internalfb.com/wiki/Manifold/Getting_Started/#cli

BUCKET=openzl_corpus

# upload all the files in the chosen (flat) directory to the manifold bucket
# usage: upload_corpus ./corp_dir pums
upload_corpus() {
  BASE_DIR=$1
  pushd "$BASE_DIR" || return
  CORPUS=$2
  manifold --prod-use-cython-client mkdir "$BUCKET/tree/$CORPUS"

  for file in *; do
    # Skip directories
    if [ -f "$file" ]; then
      file_name="${file##*/}"
      # Compress with Zstd before storing
      echo "Uploading $file_name..."
      zstd "$file_name"
      manifold --prod-use-cython-client put "$file_name.zst" "$BUCKET/tree/$CORPUS/$file_name.zst"
      rm "$file_name.zst"
    fi
  done

  echo "Upload complete. Download individual files with 'manifold --prod-use-cython-client get $BUCKET/tree/$CORPUS/<file>'"
  popd || return
}

upload_corpus $1 $2
